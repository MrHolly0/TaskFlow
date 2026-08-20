package ru.taskflow.user.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.shared.exception.RateLimitExceededException;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.infrastructure.persistence.LoginCodeJpaEntity;
import ru.taskflow.user.infrastructure.persistence.LoginCodeRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Общая для почты и телефона выдача одноразовых кодов входа.
 *
 * Длина и число попыток зависят от канала: почта — шесть цифр, миллион
 * вариантов; телефон — четыре, потолок самого механизма звонка (провайдер
 * передаёт код последними цифрами номера, из которого звонит, а такой номер
 * не бывает длиннее). Четыре цифры — это в сто раз слабее шести, поэтому для
 * телефона попыток на код меньше: 3 вместо 5 — тот же лимит в час и на
 * повторный запрос не спасает сам по себе при таком узком пространстве
 * вариантов.
 *
 * Код хранится хэшем (SHA-256), не текстом — это учётные данные, и доступ
 * к базе на чтение не должен превращаться в возможность войти чужой учёткой.
 *
 * Выдача кода разбита на две фазы: issueCode() только проверяет лимиты
 * и генерирует код, ничего не пишет в базу; confirmIssued() пишет —
 * гасит прежние коды и сохраняет новый. Вызывающая сторона обязана звать
 * confirmIssued() только после того, как код реально отправлен: иначе
 * неудачная отправка расходовала бы лимит частоты впустую.
 *
 * Нормализация идентификатора (E.164 для телефона) — забота вызывающей
 * стороны: сюда должен приходить уже приведённый к единому виду identifier,
 * этот сервис для телефона его не трогает, а для почты по-прежнему
 * приводит к нижнему регистру на всякий случай (двойная нормализация
 * почты безвредна).
 */
@Service
@RequiredArgsConstructor
public class LoginCodeService {

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS_EMAIL = 5;
    private static final int MAX_ATTEMPTS_PHONE = 3;
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_PER_HOUR = 5;
    private static final Duration RATE_WINDOW = Duration.ofHours(1);

    private final LoginCodeRepository repository;
    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    @Transactional(readOnly = true)
    public String issueCode(IdentityProvider channel, String identifier) {
        String normalized = normalize(channel, identifier);
        OffsetDateTime now = OffsetDateTime.now(clock);

        var existing = repository.findByChannelAndIdentifierOrderByCreatedAtDesc(channel, normalized);
        if (!existing.isEmpty() && existing.get(0).getCreatedAt().isAfter(now.minus(COOLDOWN))) {
            throw new RateLimitExceededException("Код уже запрошен, попробуйте через минуту");
        }
        long countLastHour = repository.countByChannelAndIdentifierAndCreatedAtAfter(
                channel, normalized, now.minus(RATE_WINDOW));
        if (countLastHour >= MAX_PER_HOUR) {
            throw new RateLimitExceededException("Слишком много запросов кода за последний час");
        }

        return generateCode(channel);
    }

    @Transactional
    public void confirmIssued(IdentityProvider channel, String identifier, String code) {
        String normalized = normalize(channel, identifier);
        OffsetDateTime now = OffsetDateTime.now(clock);

        for (var prior : repository.findByChannelAndIdentifierOrderByCreatedAtDesc(channel, normalized)) {
            if (prior.getConsumedAt() == null) {
                prior.setConsumedAt(now);
                repository.save(prior);
            }
        }

        var entity = new LoginCodeJpaEntity();
        entity.setChannel(channel);
        entity.setIdentifier(normalized);
        entity.setCodeHash(hash(code));
        entity.setExpiresAt(now.plus(CODE_TTL));
        repository.save(entity);
    }

    @Transactional
    public boolean verifyCode(IdentityProvider channel, String identifier, String code) {
        String normalized = normalize(channel, identifier);
        OffsetDateTime now = OffsetDateTime.now(clock);

        var active = repository.findByChannelAndIdentifierOrderByCreatedAtDesc(channel, normalized).stream()
                .filter(c -> c.getConsumedAt() == null)
                .findFirst();
        if (active.isEmpty()) {
            return false;
        }

        var entity = active.get();
        if (entity.getExpiresAt().isBefore(now)) {
            return false;
        }
        if (entity.getAttempts() >= maxAttempts(channel)) {
            return false;
        }

        boolean matches = MessageDigest.isEqual(
                hash(code).getBytes(StandardCharsets.UTF_8),
                entity.getCodeHash().getBytes(StandardCharsets.UTF_8));

        if (!matches) {
            entity.setAttempts(entity.getAttempts() + 1);
            repository.save(entity);
            return false;
        }

        entity.setConsumedAt(now);
        repository.save(entity);
        return true;
    }

    private int maxAttempts(IdentityProvider channel) {
        return channel == IdentityProvider.PHONE ? MAX_ATTEMPTS_PHONE : MAX_ATTEMPTS_EMAIL;
    }

    private String normalize(IdentityProvider channel, String identifier) {
        return channel == IdentityProvider.EMAIL ? identifier.toLowerCase(Locale.ROOT) : identifier;
    }

    private String generateCode(IdentityProvider channel) {
        return channel == IdentityProvider.PHONE
                ? String.format("%04d", random.nextInt(10_000))
                : String.format("%06d", random.nextInt(1_000_000));
    }

    private String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
