package ru.taskflow.assistant.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final Instant now = Instant.parse("2026-08-12T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private AssistantRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        limiter = new AssistantRateLimiter(redis, clock);
    }

    @Test
    void allow_permitsUpToLimit() {
        UUID userId = UUID.randomUUID();
        when(valueOps.increment(anyString())).thenReturn(10L);

        assertThat(limiter.allow(userId)).isTrue();
    }

    @Test
    void allow_rejectsEleventh() {
        UUID userId = UUID.randomUUID();
        when(valueOps.increment(anyString())).thenReturn(11L);

        assertThat(limiter.allow(userId)).isFalse();
    }

    @Test
    void allow_doesNotMixDifferentUsers() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();
        long bucket = now.getEpochSecond() / 60;
        when(valueOps.increment("assistant:rate:" + user1 + ":" + bucket)).thenReturn(1L);
        when(valueOps.increment("assistant:rate:" + user2 + ":" + bucket)).thenReturn(1L);

        assertThat(limiter.allow(user1)).isTrue();
        assertThat(limiter.allow(user2)).isTrue();
        verify(valueOps).increment("assistant:rate:" + user1 + ":" + bucket);
        verify(valueOps).increment("assistant:rate:" + user2 + ":" + bucket);
    }

    @Test
    void allow_setsTtlOnFirstIncrement() {
        UUID userId = UUID.randomUUID();
        when(valueOps.increment(anyString())).thenReturn(1L);

        limiter.allow(userId);

        verify(redis).expire(anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void allow_doesNotResetTtlOnSubsequentIncrements() {
        UUID userId = UUID.randomUUID();
        when(valueOps.increment(anyString())).thenReturn(2L);

        limiter.allow(userId);

        verify(redis, never()).expire(anyString(), any(Duration.class));
    }
}
