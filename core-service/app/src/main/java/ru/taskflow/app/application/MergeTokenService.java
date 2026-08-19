package ru.taskflow.app.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.taskflow.user.api.IdentityProvider;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Токен согласия на слияние учёток. Владение идентификатором к этому моменту
 * уже доказано кодом с почты или подписью Telegram Login Widget — токен не
 * про это, а про отдельное, осознанное согласие человека на перенос данных,
 * после того как он увидел состав чужой учётки. Живёт 10 минут, одноразовый:
 * consume — атомарный get-and-delete, повторный вызов с тем же токеном
 * ничего не находит и не переносит данные повторно.
 */
@Service
@RequiredArgsConstructor
public class MergeTokenService {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String PREFIX = "merge-token:";
    // Символ-разделитель из управляющего диапазона (unit separator, U+001F) —
    // не встретится ни в UUID, ни в имени enum, ни в адресе почты, так что
    // split на границе полей не ошибётся.
    private static final String SEPARATOR = "";

    private final StringRedisTemplate redis;

    public record MergeTokenData(UUID from, UUID to, IdentityProvider provider, String externalId) {}

    public String issue(UUID from, UUID to, IdentityProvider provider, String externalId) {
        String token = UUID.randomUUID().toString();
        String value = String.join(SEPARATOR, from.toString(), to.toString(), provider.name(), externalId);
        redis.opsForValue().set(PREFIX + token, value, TTL);
        return token;
    }

    public Optional<MergeTokenData> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = redis.opsForValue().getAndDelete(PREFIX + token);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(SEPARATOR, 4);
        if (parts.length != 4) {
            return Optional.empty();
        }
        return Optional.of(new MergeTokenData(
                UUID.fromString(parts[0]), UUID.fromString(parts[1]), IdentityProvider.valueOf(parts[2]), parts[3]));
    }
}
