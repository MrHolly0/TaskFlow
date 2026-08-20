package ru.taskflow.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.app.application.MergeTokenService;
import ru.taskflow.app.application.PhoneInboundWebhookRateLimiter;
import ru.taskflow.app.web.dto.UcallerInboundWebhookRequest;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskTransferResult;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.PhoneInboundConfirmationService;
import ru.taskflow.user.application.RefreshTokenService;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PhoneInboundWebhookControllerTest {

    private static final String SECRET = "correct-secret";
    private static final String PHONE = "+79991234567";
    private static final String CLIENT_NUMBER_DIGITS = "79991234567";
    private static final String CONFIRMATION_NUMBER = "79001000011";

    @Mock
    private UserService userService;
    @Mock
    private TaskService taskService;
    @Mock
    private MergeTokenService mergeTokenService;
    @Mock
    private JwtService jwtService;
    @Mock
    private RefreshTokenService refreshTokenService;
    @Mock
    private PhoneInboundConfirmationService phoneInboundConfirmationService;
    @Mock
    private PhoneInboundWebhookRateLimiter rateLimiter;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var controller = new PhoneInboundWebhookController(userService, taskService, mergeTokenService,
                jwtService, refreshTokenService, phoneInboundConfirmationService, rateLimiter, SECRET);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        org.mockito.Mockito.lenient().when(rateLimiter.allow(any())).thenReturn(true);
    }

    private UcallerInboundWebhookRequest webhook(String confirmationNumber) {
        return new UcallerInboundWebhookRequest("call-1", CLIENT_NUMBER_DIGITS, confirmationNumber,
                false, "МТС", null, "Москва");
    }

    @Test
    void wrongSecretPath_returns404WithoutTouchingAnything() throws Exception {
        mockMvc.perform(post("/api/v1/phone/inbound-webhook/wrong-secret")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER))))
                .andExpect(status().isNotFound());

        verifyNoInteractions(phoneInboundConfirmationService, userService, mergeTokenService);
    }

    @Test
    void rateLimited_returns429WithoutTouchingPending() throws Exception {
        when(rateLimiter.allow(any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER))))
                .andExpect(status().isTooManyRequests());

        verifyNoInteractions(phoneInboundConfirmationService);
    }

    @Test
    void noPendingRecordForNumber_discardedWith200() throws Exception {
        when(phoneInboundConfirmationService.consumePending(PHONE)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER))))
                .andExpect(status().isOk());

        verifyNoInteractions(userService, mergeTokenService);
    }

    // Это же представляет и истечение пяти минут: TTL в Redis стирает
    // запись, и consumePending видит её отсутствие неотличимо от «звонок
    // с чужого номера».
    @Test
    void wrongConfirmationNumber_discardedWith200WithoutBindingOrLogin() throws Exception {
        var pending = new PhoneInboundConfirmationService.Pending("different-number", "103000", null);
        when(phoneInboundConfirmationService.consumePending(PHONE)).thenReturn(Optional.of(pending));

        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER))))
                .andExpect(status().isOk());

        verifyNoInteractions(userService, mergeTokenService);
    }

    @Test
    void repeatedWebhookForSameCall_secondCallFindsNothing_doesNotProcessTwice() throws Exception {
        // getAndDelete на pending одноразовый — второй consumePending с тем
        // же номером вернёт пусто, как повело бы себя реальное хранилище.
        var pending = new PhoneInboundConfirmationService.Pending(CONFIRMATION_NUMBER, "103000", null);
        when(phoneInboundConfirmationService.consumePending(PHONE))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.empty());
        when(userService.findOrCreateByIdentity(eq(IdentityProvider.PHONE), eq(PHONE), any()))
                .thenReturn(new UserDto(UUID.randomUUID(), null, null, null));

        var body = objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER));
        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET).contentType("application/json").content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET).contentType("application/json").content(body))
                .andExpect(status().isOk());

        verify(userService, org.mockito.Mockito.times(1)).findOrCreateByIdentity(eq(IdentityProvider.PHONE), eq(PHONE), any());
    }

    @Test
    void missingClientNumberOrConfirmationNumber_discardedWith200() throws Exception {
        var incomplete = new UcallerInboundWebhookRequest("call-1", null, CONFIRMATION_NUMBER, false, null, null, null);

        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(incomplete)))
                .andExpect(status().isOk());

        verifyNoInteractions(phoneInboundConfirmationService);
    }

    @Test
    void loginContext_matchingConfirmation_createsIdentityAndStoresLoginResult() throws Exception {
        var pending = new PhoneInboundConfirmationService.Pending(CONFIRMATION_NUMBER, "103000", null);
        when(phoneInboundConfirmationService.consumePending(PHONE)).thenReturn(Optional.of(pending));
        UUID userId = UUID.randomUUID();
        when(userService.findOrCreateByIdentity(eq(IdentityProvider.PHONE), eq(PHONE), any(UserProfile.class)))
                .thenReturn(new UserDto(userId, null, null, null));
        when(jwtService.issueAccessToken(eq(userId), any())).thenReturn("access-token");
        when(refreshTokenService.issue(userId)).thenReturn("refresh-token");

        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER))))
                .andExpect(status().isOk());

        verify(phoneInboundConfirmationService).storeLoginResult(PHONE, "access-token", "refresh-token");
        verifyNoInteractions(mergeTokenService);
    }

    @Test
    void bindContext_noConflict_bindsIdentityAndStoresBindResult() throws Exception {
        UUID userId = UUID.randomUUID();
        var pending = new PhoneInboundConfirmationService.Pending(CONFIRMATION_NUMBER, "103000", userId);
        when(phoneInboundConfirmationService.consumePending(PHONE)).thenReturn(Optional.of(pending));
        when(userService.findIdentityOwner(IdentityProvider.PHONE, PHONE)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER))))
                .andExpect(status().isOk());

        verify(userService).bindIdentity(userId, IdentityProvider.PHONE, PHONE);
        verify(phoneInboundConfirmationService).storeBindResult(eq(PHONE), any());
        verifyNoInteractions(mergeTokenService, jwtService);
    }

    @Test
    void bindContext_conflict_storesConflictResultWithoutTransferring() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        var pending = new PhoneInboundConfirmationService.Pending(CONFIRMATION_NUMBER, "103000", userId);
        when(phoneInboundConfirmationService.consumePending(PHONE)).thenReturn(Optional.of(pending));
        when(userService.findIdentityOwner(IdentityProvider.PHONE, PHONE)).thenReturn(Optional.of(otherUserId));
        when(taskService.countOwnership(otherUserId)).thenReturn(new TaskTransferResult(115, 12, 3));
        when(mergeTokenService.issue(otherUserId, userId, IdentityProvider.PHONE, PHONE)).thenReturn("merge-token-abc");

        mockMvc.perform(post("/api/v1/phone/inbound-webhook/" + SECRET)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(webhook(CONFIRMATION_NUMBER))))
                .andExpect(status().isOk());

        verify(phoneInboundConfirmationService).storeConflictResult(PHONE, 115, 12, 3, "merge-token-abc");
        verify(userService, never()).bindIdentity(any(), any(), any());
    }
}
