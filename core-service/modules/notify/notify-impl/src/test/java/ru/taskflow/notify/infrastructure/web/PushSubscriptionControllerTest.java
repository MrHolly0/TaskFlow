package ru.taskflow.notify.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionRepository;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PushSubscriptionControllerTest {

    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc123";

    @Mock
    private PushSubscriptionRepository repository;

    private final UUID userId = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new PushSubscriptionController(repository);
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
    void subscribe_newEndpoint_createsSubscriptionOwnedByCurrentUser() throws Exception {
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/push/subscriptions")
                        .contentType("application/json")
                        .header("User-Agent", "TestAgent/1.0")
                        .content(subscribeBody(ENDPOINT, "p256dh-key", "auth-key")))
                .andExpect(status().isOk());

        ArgumentCaptor<PushSubscriptionJpaEntity> captor = ArgumentCaptor.forClass(PushSubscriptionJpaEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getEndpoint()).isEqualTo(ENDPOINT);
        assertThat(captor.getValue().getP256dh()).isEqualTo("p256dh-key");
        assertThat(captor.getValue().getAuth()).isEqualTo("auth-key");
        assertThat(captor.getValue().getUserAgent()).isEqualTo("TestAgent/1.0");
    }

    @Test
    void subscribe_existingEndpointFromDifferentAccount_reassignsOwnerInstead() throws Exception {
        // Тот же браузерный endpoint, повторно зарегистрированный под другим
        // логином (общий компьютер) — подписка переходит к текущему аккаунту,
        // а не дублируется.
        var existing = new PushSubscriptionJpaEntity();
        existing.setUserId(UUID.randomUUID());
        existing.setEndpoint(ENDPOINT);
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        mockMvc.perform(post("/api/v1/push/subscriptions")
                        .contentType("application/json")
                        .content(subscribeBody(ENDPOINT, "p256dh-key", "auth-key")))
                .andExpect(status().isOk());

        ArgumentCaptor<PushSubscriptionJpaEntity> captor = ArgumentCaptor.forClass(PushSubscriptionJpaEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    }

    @Test
    void subscribe_blankEndpoint_rejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/v1/push/subscriptions")
                        .contentType("application/json")
                        .content(subscribeBody("", "p256dh-key", "auth-key")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsubscribe_deletesMatchingSubscription() throws Exception {
        var existing = new PushSubscriptionJpaEntity();
        existing.setEndpoint(ENDPOINT);
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.of(existing));

        mockMvc.perform(delete("/api/v1/push/subscriptions").param("endpoint", ENDPOINT))
                .andExpect(status().isNoContent());

        verify(repository).delete(existing);
    }

    @Test
    void unsubscribe_unknownEndpoint_doesNothingButStillReturns204() throws Exception {
        when(repository.findByEndpoint(ENDPOINT)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/v1/push/subscriptions").param("endpoint", ENDPOINT))
                .andExpect(status().isNoContent());

        verify(repository, never()).delete(any());
    }

    private String subscribeBody(String endpoint, String p256dh, String auth) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "endpoint", endpoint,
                "keys", java.util.Map.of("p256dh", p256dh, "auth", auth)
        ));
    }
}
