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
 * Одноразовые коды входа на почту. Раньше тем же путём шёл и телефон, но
 * подтверждение номера теперь устроено иначе — входящим звонком, без кода
 * вообще (см. PhoneInboundConfirmationService) — телефон сюда больше не
 * заходит, канал остался параметром на случай, если понадобится ещё один
 * код-based способ, а не потому что EMAIL — единственно возможное значение.
 *
 * Код хранится хэшем (SHA-256), не текстом — это учётные данные, и доступ
 * к базе на чтение не должен превращаться в возможность войти чужой учёткой.
 *
 * Выдача кода разбита на две фазы: issueCode() только проверяет лимиты
 * и генерирует код, ничего не пишет в базу; confirmIssued() пишет —
 * гасит прежние коды и сохраняет новый. Вызывающая сторона обязана звать
 * confirmIssued() только после того, как код реально отправлен: иначе
 * неудачная отправка расходовала бы лимит частоты впустую.
 */
@Service
@RequiredArgsConstructor
public class LoginCodeService {

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 5;
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
        if (entity.getAttempts() >= MAX_ATTEMPTS) {
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

    private String normalize(IdentityProvider channel, String identifier) {
        return channel == IdentityProvider.EMAIL ? identifier.toLowerCase(Locale.ROOT) : identifier;
    }

    private String generateCode(IdentityProvider channel) {
        return String.format("%06d", random.nextInt(1_000_000));
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
