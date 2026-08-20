package ru.taskflow.app.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Точка приёма не аутентифицирована (Ucaller не умеет присылать наш JWT) —
 * лимитировать по IP, как AuthRateLimiter для /auth/**. Отдельный лимитер,
 * а не переиспользование AuthRateLimiter: разный смысл превышения — там
 * перебор входа, здесь — шторм на точку приёма вебхука.
 */
@Component
@RequiredArgsConstructor
public class PhoneInboundWebhookRateLimiter {

    private static final int LIMIT_PER_MINUTE = 60;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public boolean allow(String clientIp) {
        String key = "phone-inbound-webhook:rate:" + clientIp + ":" + currentMinuteBucket();
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        return count == null || count <= LIMIT_PER_MINUTE;
    }

    private long currentMinuteBucket() {
        return Instant.now(clock).getEpochSecond() / 60;
    }
}
