package ru.taskflow.user.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Ожидающая запись входящего подтверждения и результат его обработки — в
 * Redis, тем же приёмом, что и токены слияния (см. MergeTokenService):
 * значение строкой через разделитель, срок жизни на самом ключе.
 *
 * Две записи на номер, а не одна, потому что подтверждение приходит
 * асинхронно вебхуком (см. PhoneInboundWebhookController), а хочет знать про
 * него браузер, который в этот момент ждёт на опросе. Ожидающая запись
 * (pending) — то, что вебхук ищет и гасит одноразово по getAndDelete;
 * результат (result) — то, что вебхук кладёт по итогу и что забирает опрос,
 * тоже одноразово. TTL результата короче: это лишь окно на то, чтобы опрос
 * успел его увидеть, а не отдельное состояние ожидания.
 */
@Service
@RequiredArgsConstructor
public class PhoneInboundConfirmationService {

    private static final Duration PENDING_TTL = Duration.ofMinutes(5);
    private static final Duration RESULT_TTL = Duration.ofMinutes(2);
    private static final String PENDING_PREFIX = "phone-inbound:pending:";
    private static final String RESULT_PREFIX = "phone-inbound:result:";
    // Символ-разделитель из управляющего диапазона (unit separator, U+001F) —
    // тот же приём, что в MergeTokenService, и по той же причине: не
    // встретится ни в UUID, ни в токене, ни в номере телефона.
    private static final String SEPARATOR = "";

    private final StringRedisTemplate redis;

    public record Pending(String confirmationNumber, String ucallerId, UUID boundUserId) {}

    public sealed interface Result permits LoginResult, BindResult, ConflictResult {}

    public record LoginResult(String accessToken, String refreshToken) implements Result {}

    public record BindResult(OffsetDateTime verifiedAt) implements Result {}

    public record ConflictResult(int tasks, int groups, int tags, String mergeToken) implements Result {}

    /**
     * @param boundUserId учётка, к которой привязываем — null при входе,
     *                    когда учётка ещё не определена
     */
    public void createPending(String phoneE164, String confirmationNumber, String ucallerId, UUID boundUserId) {
        String value = String.join(SEPARATOR,
                confirmationNumber,
                ucallerId == null ? "" : ucallerId,
                boundUserId == null ? "" : boundUserId.toString());
        redis.opsForValue().set(PENDING_PREFIX + phoneE164, value, PENDING_TTL);
    }

    public boolean hasPending(String phoneE164) {
        return Boolean.TRUE.equals(redis.hasKey(PENDING_PREFIX + phoneE164));
    }

    public void cancelPending(String phoneE164) {
        redis.delete(PENDING_PREFIX + phoneE164);
    }

    /**
     * Атомарно забирает и гасит ожидающую запись независимо от того, что в
     * ней — повторная доставка того же вебхука (тот же callId у Ucaller
     * несёт тот же confirmationNumber) уже не найдёт ничего и будет
     * отброшена сама по себе. Сверка confirmationNumber — на вызывающей
     * стороне (см. PhoneInboundWebhookController), с уже изъятым значением.
     */
    public Optional<Pending> consumePending(String phoneE164) {
        String raw = redis.opsForValue().getAndDelete(PENDING_PREFIX + phoneE164);
        if (raw == null) {
            return Optional.empty();
        }
        String[] parts = raw.split(SEPARATOR, -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        UUID boundUserId = parts[2].isEmpty() ? null : UUID.fromString(parts[2]);
        return Optional.of(new Pending(parts[0], parts[1].isEmpty() ? null : parts[1], boundUserId));
    }

    public void storeLoginResult(String phoneE164, String accessToken, String refreshToken) {
        String value = String.join(SEPARATOR, "LOGIN", accessToken, refreshToken);
        redis.opsForValue().set(RESULT_PREFIX + phoneE164, value, RESULT_TTL);
    }

    public void storeBindResult(String phoneE164, OffsetDateTime verifiedAt) {
        String value = String.join(SEPARATOR, "BIND", verifiedAt.toString());
        redis.opsForValue().set(RESULT_PREFIX + phoneE164, value, RESULT_TTL);
    }

    public void storeConflictResult(String phoneE164, int tasks, int groups, int tags, String mergeToken) {
        String value = String.join(SEPARATOR, "CONFLICT",
                String.valueOf(tasks), String.valueOf(groups), String.valueOf(tags), mergeToken);
        redis.opsForValue().set(RESULT_PREFIX + phoneE164, value, RESULT_TTL);
    }

    /**
     * Одноразово: второй опрос после успешного уже ничего не найдёт, как и
     * повторная доставка вебхука для pending выше.
     */
    public Optional<Result> pollResult(String phoneE164) {
        String raw = redis.opsForValue().getAndDelete(RESULT_PREFIX + phoneE164);
        if (raw == null) {
            return Optional.empty();
        }
        String[] parts = raw.split(SEPARATOR, -1);
        return switch (parts[0]) {
            case "LOGIN" -> parts.length == 3
                    ? Optional.of(new LoginResult(parts[1], parts[2]))
                    : Optional.empty();
            case "BIND" -> parts.length == 2
                    ? Optional.of(new BindResult(OffsetDateTime.parse(parts[1])))
                    : Optional.empty();
            case "CONFLICT" -> parts.length == 5
                    ? Optional.of(new ConflictResult(
                            Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), parts[4]))
                    : Optional.empty();
            default -> Optional.empty();
        };
    }
}
