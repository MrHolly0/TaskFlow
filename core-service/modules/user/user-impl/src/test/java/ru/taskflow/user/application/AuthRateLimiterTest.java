package ru.taskflow.user.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final Instant now = Instant.parse("2026-08-19T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final long bucket = now.getEpochSecond() / 60;

    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        limiter = new AuthRateLimiter(redis, clock);
    }

    @Test
    void allow_permitsUpToLimit() {
        when(valueOps.increment(anyString())).thenReturn(30L);

        assertThat(limiter.allow(requestFrom("1.2.3.4"))).isTrue();
    }

    @Test
    void allow_rejectsOverLimit() {
        when(valueOps.increment(anyString())).thenReturn(31L);

        assertThat(limiter.allow(requestFrom("1.2.3.4"))).isFalse();
    }

    @Test
    void allow_keysByRealIpHeaderWhenPresent() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.9");
        when(valueOps.increment("auth:rate:ip:203.0.113.9:" + bucket)).thenReturn(1L);

        limiter.allow(request);

        org.mockito.Mockito.verify(valueOps).increment("auth:rate:ip:203.0.113.9:" + bucket);
    }

    @Test
    void allow_fallsBackToRemoteAddrWithoutRealIpHeader() {
        when(valueOps.increment("auth:rate:ip:198.51.100.7:" + bucket)).thenReturn(1L);

        limiter.allow(requestFrom("198.51.100.7"));

        org.mockito.Mockito.verify(valueOps).increment("auth:rate:ip:198.51.100.7:" + bucket);
    }

    @Test
    void allow_doesNotMixDifferentIps() {
        when(valueOps.increment("auth:rate:ip:1.1.1.1:" + bucket)).thenReturn(1L);
        when(valueOps.increment("auth:rate:ip:2.2.2.2:" + bucket)).thenReturn(1L);

        assertThat(limiter.allow(requestFrom("1.1.1.1"))).isTrue();
        assertThat(limiter.allow(requestFrom("2.2.2.2"))).isTrue();
    }

    @Test
    void allowForEmailConfirm_permitsUpToNarrowerLimit() {
        when(valueOps.increment(anyString())).thenReturn(5L);

        assertThat(limiter.allowForEmailConfirm(requestFrom("1.2.3.4"), "user@example.com")).isTrue();
    }

    @Test
    void allowForEmailConfirm_rejectsOverNarrowerLimit() {
        when(valueOps.increment(anyString())).thenReturn(6L);

        assertThat(limiter.allowForEmailConfirm(requestFrom("1.2.3.4"), "user@example.com")).isFalse();
    }

    @Test
    void allowForEmailConfirm_normalizesEmailCase() {
        when(valueOps.increment("auth:rate:ip-email:1.2.3.4:user@example.com:" + bucket)).thenReturn(1L);

        limiter.allowForEmailConfirm(requestFrom("1.2.3.4"), "User@Example.com");

        org.mockito.Mockito.verify(valueOps).increment("auth:rate:ip-email:1.2.3.4:user@example.com:" + bucket);
    }

    @Test
    void allow_setsTtlOnFirstIncrement() {
        when(valueOps.increment(anyString())).thenReturn(1L);

        limiter.allow(requestFrom("1.2.3.4"));

        org.mockito.Mockito.verify(redis).expire(anyString(), eq(Duration.ofSeconds(60)));
    }

    private MockHttpServletRequest requestFrom(String ip) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }
}
