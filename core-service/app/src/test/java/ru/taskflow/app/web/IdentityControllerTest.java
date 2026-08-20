package ru.taskflow.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.app.application.AccountTransferResult;
import ru.taskflow.app.application.AccountTransferService;
import ru.taskflow.app.application.MergeTokenService;
import ru.taskflow.app.web.dto.MergeRequest;
import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.shared.security.TelegramLoginWidgetValidator;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskTransferResult;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.IdentityDto;
import ru.taskflow.user.application.EmailSender;
import ru.taskflow.user.application.LoginCodeService;
import ru.taskflow.user.infrastructure.web.dto.VerifyCodeRequest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class IdentityControllerTest {

    private static final String EMAIL = "user@example.com";
    private static final String CODE = "123456";

    @Mock
    private UserService userService;
    @Mock
    private TaskService taskService;
    @Mock
    private LoginCodeService loginCodeService;
    @Mock
    private EmailSender emailSender;
    @Mock
    private TelegramLoginWidgetValidator loginWidgetValidator;
    @Mock
    private AccountTransferService accountTransferService;
    @Mock
    private MergeTokenService mergeTokenService;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new IdentityController(userService, taskService, loginCodeService, emailSender,
                loginWidgetValidator, accountTransferService, mergeTokenService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthenticatedUser(userId, "user"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmEmail_noConflict_bindsImmediatelyWithoutToken() throws Exception {
        when(loginCodeService.verifyCode(IdentityProvider.EMAIL, EMAIL, CODE)).thenReturn(true);
        when(userService.findIdentityOwner(IdentityProvider.EMAIL, EMAIL)).thenReturn(Optional.empty());
        when(userService.bindIdentity(userId, IdentityProvider.EMAIL, EMAIL))
                .thenReturn(new IdentityDto(IdentityProvider.EMAIL, EMAIL, OffsetDateTime.now()));

        mockMvc.perform(post("/api/v1/identities/email/confirm")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new VerifyCodeRequest(EMAIL, CODE))))
                .andExpect(status().isOk());

        verifyNoInteractions(accountTransferService, mergeTokenService);
    }

    @Test
    void confirmEmail_conflict_doesNotTransferAndReturns409WithSummaryAndToken() throws Exception {
        when(loginCodeService.verifyCode(IdentityProvider.EMAIL, EMAIL, CODE)).thenReturn(true);
        when(userService.findIdentityOwner(IdentityProvider.EMAIL, EMAIL)).thenReturn(Optional.of(otherUserId));
        when(taskService.countOwnership(otherUserId)).thenReturn(new TaskTransferResult(115, 12, 3));
        when(mergeTokenService.issue(otherUserId, userId, IdentityProvider.EMAIL, EMAIL)).thenReturn("merge-token-abc");

        mockMvc.perform(post("/api/v1/identities/email/confirm")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new VerifyCodeRequest(EMAIL, CODE))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.tasks").value(115))
                .andExpect(jsonPath("$.groups").value(12))
                .andExpect(jsonPath("$.tags").value(3))
                .andExpect(jsonPath("$.mergeToken").value("merge-token-abc"));

        verifyNoInteractions(accountTransferService);
        verify(userService, never()).bindIdentity(any(), any(), anyString());
    }

    @Test
    void merge_unknownOrExpiredToken_returns401AndDoesNotTransfer() throws Exception {
        when(mergeTokenService.consume("garbage")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/identities/merge")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new MergeRequest("garbage"))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(accountTransferService);
    }

    @Test
    void merge_blankToken_rejectedByValidationBeforeReachingService() throws Exception {
        mockMvc.perform(post("/api/v1/identities/merge")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new MergeRequest(""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(mergeTokenService, accountTransferService);
    }

    @Test
    void merge_validToken_transfersAndBinds() throws Exception {
        var data = new MergeTokenService.MergeTokenData(otherUserId, userId, IdentityProvider.EMAIL, EMAIL);
        when(mergeTokenService.consume("good-token")).thenReturn(Optional.of(data));
        when(accountTransferService.transfer(otherUserId, userId))
                .thenReturn(new AccountTransferResult(115, 12, 3, 4, 200, 1, 1));
        when(userService.bindIdentity(userId, IdentityProvider.EMAIL, EMAIL))
                .thenReturn(new IdentityDto(IdentityProvider.EMAIL, EMAIL, OffsetDateTime.now()));

        mockMvc.perform(post("/api/v1/identities/merge")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new MergeRequest("good-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mergedFrom.tasks").value(115));

        verify(accountTransferService).transfer(otherUserId, userId);
    }

    @Test
    void merge_tokenIssuedToDifferentAccount_returnsForbidden() throws Exception {
        var data = new MergeTokenService.MergeTokenData(otherUserId, UUID.randomUUID(), IdentityProvider.EMAIL, EMAIL);
        when(mergeTokenService.consume("stolen-token")).thenReturn(Optional.of(data));

        mockMvc.perform(post("/api/v1/identities/merge")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new MergeRequest("stolen-token"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accountTransferService);
    }

    @Test
    void merge_repeatedCallWithSameToken_secondCallFindsNothing_noDuplicateTransfer() throws Exception {
        // getAndDelete в Redis одноразовый — второй consume с тем же токеном
        // вернёт пусто. Здесь это смоделировано явно: первый вызов находит
        // данные, второй — нет, как повело бы себя реальное хранилище.
        var data = new MergeTokenService.MergeTokenData(otherUserId, userId, IdentityProvider.EMAIL, EMAIL);
        when(mergeTokenService.consume("one-shot-token"))
                .thenReturn(Optional.of(data))
                .thenReturn(Optional.empty());
        when(accountTransferService.transfer(otherUserId, userId))
                .thenReturn(new AccountTransferResult(1, 0, 0, 0, 0, 0, 0));
        when(userService.bindIdentity(userId, IdentityProvider.EMAIL, EMAIL))
                .thenReturn(new IdentityDto(IdentityProvider.EMAIL, EMAIL, OffsetDateTime.now()));

        var request = objectMapper.writeValueAsString(new MergeRequest("one-shot-token"));

        mockMvc.perform(post("/api/v1/identities/merge").contentType("application/json").content(request))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/identities/merge").contentType("application/json").content(request))
                .andExpect(status().isUnauthorized());

        verify(accountTransferService).transfer(otherUserId, userId);
    }
}
