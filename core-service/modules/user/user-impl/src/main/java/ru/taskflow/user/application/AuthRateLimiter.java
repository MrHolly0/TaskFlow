package ru.taskflow.user.application;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Точки /auth/** не аутентифицированы — лимитировать по userId, как
 * AssistantRateLimiter, нечем, поэтому ключ здесь — IP. За обратным прокси
 * (nginx.conf) реальный адрес приходит в X-Real-IP; без прокси (локально)
 * используем адрес соединения напрямую.
 * <p>
 * Два независимых лимита: общий по IP на любую точку входа — защита от
 * перебора вообще, и более узкий по паре (IP, адрес почты) — специально для
 * подтверждения кода, где перебор идёт запросом кода заново, а не только
 * попытками ввода на один код (тот лимит уже есть в LoginCodeService).
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimiter {

    private static final int IP_LIMIT_PER_MINUTE = 30;
    private static final int IP_EMAIL_LIMIT_PER_MINUTE = 5;
    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public boolean allow(HttpServletRequest request) {
        return increment("auth:rate:ip:" + clientIp(request), IP_LIMIT_PER_MINUTE);
    }

    public boolean allowForEmailConfirm(HttpServletRequest request, String email) {
        String key = "auth:rate:ip-email:" + clientIp(request) + ":" + email.toLowerCase(Locale.ROOT);
        return increment(key, IP_EMAIL_LIMIT_PER_MINUTE);
    }

    private boolean increment(String keyPrefix, int limit) {
        String key = keyPrefix + ":" + currentMinuteBucket();
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        return count == null || count <= limit;
    }

    private long currentMinuteBucket() {
        return Instant.now(clock).getEpochSecond() / 60;
    }

    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr();
    }
}
