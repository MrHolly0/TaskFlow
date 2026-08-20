package ru.taskflow.app.application;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneInboundWebhookRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private final Instant now = Instant.parse("2026-08-19T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final long bucket = now.getEpochSecond() / 60;

    private PhoneInboundWebhookRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        limiter = new PhoneInboundWebhookRateLimiter(redis, clock);
    }

    @Test
    void allow_permitsUpToLimit() {
        when(valueOps.increment(anyString())).thenReturn(60L);

        assertThat(limiter.allow("1.2.3.4")).isTrue();
    }

    @Test
    void allow_rejectsOverLimit() {
        when(valueOps.increment(anyString())).thenReturn(61L);

        assertThat(limiter.allow("1.2.3.4")).isFalse();
    }

    @Test
    void allow_doesNotMixDifferentIps() {
        when(valueOps.increment("phone-inbound-webhook:rate:1.1.1.1:" + bucket)).thenReturn(1L);
        when(valueOps.increment("phone-inbound-webhook:rate:2.2.2.2:" + bucket)).thenReturn(1L);

        assertThat(limiter.allow("1.1.1.1")).isTrue();
        assertThat(limiter.allow("2.2.2.2")).isTrue();
    }

    @Test
    void allow_setsTtlOnFirstIncrement() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        limiter.allow("1.2.3.4");

        verify(redis).expire(anyString(), eq(Duration.ofSeconds(60)));
    }
}
