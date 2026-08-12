package ru.taskflow.assistant.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.exception.ProposalNotFoundException;
import ru.taskflow.assistant.application.AssistantRateLimiter;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AssistantControllerTest {

    @Mock
    private AssistantService assistantService;

    @Mock
    private AssistantRateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new AssistantController(assistantService, rateLimiter);
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
    void handleMessage_returnsCreatedProposal() throws Exception {
        when(rateLimiter.allow(userId)).thenReturn(true);
        when(assistantService.handleText(eq(userId), eq("закрой молоко"), eq(AssistantChannel.WEB)))
                .thenReturn(proposal());

        mockMvc.perform(multipart("/api/v1/assistant/messages").param("text", "закрой молоко"))
                .andExpect(status().isCreated());
    }

    @Test
    void handleMessage_rejectsWhenRateLimited() throws Exception {
        when(rateLimiter.allow(userId)).thenReturn(false);

        mockMvc.perform(multipart("/api/v1/assistant/messages").param("text", "закрой молоко"))
                .andExpect(status().isTooManyRequests());

        verifyNoInteractions(assistantService);
    }

    @Test
    void findById_returnsProposal() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.findById(userId, id)).thenReturn(proposal());

        mockMvc.perform(get("/api/v1/assistant/proposals/{id}", id))
                .andExpect(status().isOk());
    }

    @Test
    void findById_returnsNotFoundForForeignProposal() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.findById(userId, id)).thenThrow(new ProposalNotFoundException(id));

        mockMvc.perform(get("/api/v1/assistant/proposals/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void setActionAccepted_returnsProposal() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.setActionAccepted(userId, id, 1, false)).thenReturn(proposal());

        mockMvc.perform(patch("/api/v1/assistant/proposals/{id}/actions/{ordinal}", id, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void setActionAccepted_rejectsInvalidBody() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/assistant/proposals/{id}/actions/{ordinal}", id, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assistantService);
    }

    @Test
    void apply_returnsApplyResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(assistantService.apply(userId, id))
                .thenReturn(new ApplyResult(ProposalStatus.APPLIED, 1, 1, List.of()));

        mockMvc.perform(post("/api/v1/assistant/proposals/{id}/apply", id))
                .andExpect(status().isOk());
    }

    @Test
    void reject_returnsNoContent() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/assistant/proposals/{id}/reject", id))
                .andExpect(status().isNoContent());

        verify(assistantService).reject(userId, id);
    }

    private Proposal proposal() {
        return new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), OffsetDateTime.now(), OffsetDateTime.now().plusHours(24));
    }
}
