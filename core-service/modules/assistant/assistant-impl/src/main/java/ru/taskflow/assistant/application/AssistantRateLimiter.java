package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Защита бюджета от зациклившегося клиента, а не тарифный лимит. Счётчик по
 * минутному окну: ключ включает текущую минуту эпохи, поэтому TTL нужен только
 * на случай, если Redis переживёт минуту дольше клиента — сам счётчик стареет
 * вместе со сменой ключа.
 */
@Component
@RequiredArgsConstructor
public class AssistantRateLimiter {

    private static final int LIMIT_PER_MINUTE = 10;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public boolean allow(UUID userId) {
        String key = "assistant:rate:" + userId + ":" + currentMinuteBucket();
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
