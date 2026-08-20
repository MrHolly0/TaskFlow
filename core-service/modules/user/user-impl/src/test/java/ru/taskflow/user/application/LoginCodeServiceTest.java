package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.shared.exception.RateLimitExceededException;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.infrastructure.persistence.LoginCodeJpaEntity;
import ru.taskflow.user.infrastructure.persistence.LoginCodeRepository;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginCodeServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PHONE = "+79991234567";
    private static final Instant NOW_INSTANT = Instant.parse("2026-08-14T12:00:00Z");

    @Mock
    private LoginCodeRepository repository;

    private final Clock clock = Clock.fixed(NOW_INSTANT, ZoneOffset.UTC);
    private LoginCodeService service;

    private void newService() {
        service = new LoginCodeService(repository, clock);
    }

    @Test
    void issueCode_noPriorCodes_returnsSixDigitCodeForEmail() {
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of());
        when(repository.countByChannelAndIdentifierAndCreatedAtAfter(any(), any(), any())).thenReturn(0L);
        newService();

        String code = service.issueCode(IdentityProvider.EMAIL, EMAIL);

        assertThat(code).matches("\\d{6}");
        verify(repository, never()).save(any());
    }

    @Test
    void issueCode_noPriorCodes_returnsFourDigitCodeForPhone() {
        // Четыре цифры — потолок самого механизма звонка (Ucaller), не выбор
        // ради простоты; отдельно проверяем, что для телефона именно так.
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.PHONE, PHONE)).thenReturn(List.of());
        when(repository.countByChannelAndIdentifierAndCreatedAtAfter(any(), any(), any())).thenReturn(0L);
        newService();

        String code = service.issueCode(IdentityProvider.PHONE, PHONE);

        assertThat(code).matches("\\d{4}");
    }

    @Test
    void issueCode_lowercasesEmailForRateCheck() {
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, "user@example.com")).thenReturn(List.of());
        when(repository.countByChannelAndIdentifierAndCreatedAtAfter(any(), any(), any())).thenReturn(0L);
        newService();

        service.issueCode(IdentityProvider.EMAIL, "User@Example.com");

        verify(repository).findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, "user@example.com");
    }

    @Test
    void issueCode_doesNotChangePhoneCasing() {
        // Телефон приходит уже нормализованным к E.164 вызывающей стороной —
        // сервис не должен пытаться его как-то преобразовывать сам.
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.PHONE, PHONE)).thenReturn(List.of());
        when(repository.countByChannelAndIdentifierAndCreatedAtAfter(any(), any(), any())).thenReturn(0L);
        newService();

        service.issueCode(IdentityProvider.PHONE, PHONE);

        verify(repository).findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.PHONE, PHONE);
    }

    @Test
    void issueCode_withinCooldown_throwsRateLimitException() {
        var recent = activeCode(IdentityProvider.EMAIL, EMAIL, now().minusSeconds(30), 0);
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of(recent));
        newService();

        assertThatThrownBy(() -> service.issueCode(IdentityProvider.EMAIL, EMAIL))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void issueCode_fiveInLastHour_throwsRateLimitException() {
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of());
        when(repository.countByChannelAndIdentifierAndCreatedAtAfter(IdentityProvider.EMAIL, EMAIL, now().minusHours(1)))
                .thenReturn(5L);
        newService();

        assertThatThrownBy(() -> service.issueCode(IdentityProvider.EMAIL, EMAIL))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void confirmIssued_noPriorCodes_savesHashedCode() {
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of());
        newService();
        String code = "123456";

        service.confirmIssued(IdentityProvider.EMAIL, EMAIL, code);

        ArgumentCaptor<LoginCodeJpaEntity> captor = ArgumentCaptor.forClass(LoginCodeJpaEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(IdentityProvider.EMAIL);
        assertThat(captor.getValue().getIdentifier()).isEqualTo(EMAIL);
        assertThat(captor.getValue().getCodeHash()).isEqualTo(sha256(code));
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(now().plusMinutes(10));
    }

    @Test
    void confirmIssued_lowercasesEmail() {
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, "user@example.com")).thenReturn(List.of());
        newService();

        service.confirmIssued(IdentityProvider.EMAIL, "User@Example.com", "123456");

        verify(repository).findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, "user@example.com");
    }

    @Test
    void confirmIssued_invalidatesPreviousUnconsumedCode() {
        var previous = activeCode(IdentityProvider.EMAIL, EMAIL, now().minusSeconds(90), 0);
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of(previous));
        newService();

        service.confirmIssued(IdentityProvider.EMAIL, EMAIL, "123456");

        assertThat(previous.getConsumedAt()).isEqualTo(now());
        verify(repository).save(previous);
    }

    @Test
    void verifyCode_correctCode_succeedsAndConsumesCode() {
        String rawCode = "123456";
        var entity = activeCode(IdentityProvider.EMAIL, EMAIL, now(), 0);
        entity.setCodeHash(sha256(rawCode));
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of(entity));
        newService();

        boolean result = service.verifyCode(IdentityProvider.EMAIL, EMAIL, rawCode);

        assertThat(result).isTrue();
        assertThat(entity.getConsumedAt()).isEqualTo(now());
    }

    @Test
    void verifyCode_wrongCode_incrementsAttemptsAndFails() {
        var entity = activeCode(IdentityProvider.EMAIL, EMAIL, now(), 0);
        entity.setCodeHash(sha256("123456"));
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of(entity));
        newService();

        boolean result = service.verifyCode(IdentityProvider.EMAIL, EMAIL, "000000");

        assertThat(result).isFalse();
        assertThat(entity.getAttempts()).isEqualTo(1);
        assertThat(entity.getConsumedAt()).isNull();
    }

    @Test
    void verifyCode_sixthAttempt_rejectedEvenWithCorrectCode() {
        String rawCode = "123456";
        var entity = activeCode(IdentityProvider.EMAIL, EMAIL, now(), 5);
        entity.setCodeHash(sha256(rawCode));
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of(entity));
        newService();

        boolean result = service.verifyCode(IdentityProvider.EMAIL, EMAIL, rawCode);

        assertThat(result).isFalse();
        assertThat(entity.getConsumedAt()).isNull();
    }

    @Test
    void verifyCode_phoneChannel_rejectedOnThirdAttemptNotFifth() {
        // Телефон — три попытки, не пять: код в сто раз слабее (4 цифры
        // против 6), и лимит частоты один на всех каналов этого не компенсирует.
        String rawCode = "1234";
        var entity = activeCode(IdentityProvider.PHONE, PHONE, now(), 3);
        entity.setCodeHash(sha256(rawCode));
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.PHONE, PHONE)).thenReturn(List.of(entity));
        newService();

        boolean result = service.verifyCode(IdentityProvider.PHONE, PHONE, rawCode);

        assertThat(result).isFalse();
    }

    @Test
    void verifyCode_phoneChannel_secondAttemptStillAllowed() {
        String rawCode = "1234";
        var entity = activeCode(IdentityProvider.PHONE, PHONE, now(), 2);
        entity.setCodeHash(sha256(rawCode));
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.PHONE, PHONE)).thenReturn(List.of(entity));
        newService();

        boolean result = service.verifyCode(IdentityProvider.PHONE, PHONE, rawCode);

        assertThat(result).isTrue();
    }

    @Test
    void verifyCode_expiredCode_fails() {
        String rawCode = "123456";
        var entity = new LoginCodeJpaEntity();
        entity.setChannel(IdentityProvider.EMAIL);
        entity.setIdentifier(EMAIL);
        entity.setCodeHash(sha256(rawCode));
        entity.setAttempts(0);
        entity.setExpiresAt(now().minusSeconds(1));
        entity.setCreatedAt(now().minusMinutes(11));
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of(entity));
        newService();

        boolean result = service.verifyCode(IdentityProvider.EMAIL, EMAIL, rawCode);

        assertThat(result).isFalse();
    }

    @Test
    void verifyCode_alreadyConsumedCode_fails() {
        String rawCode = "123456";
        var entity = activeCode(IdentityProvider.EMAIL, EMAIL, now(), 0);
        entity.setCodeHash(sha256(rawCode));
        entity.setConsumedAt(now());
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of(entity));
        newService();

        boolean result = service.verifyCode(IdentityProvider.EMAIL, EMAIL, rawCode);

        assertThat(result).isFalse();
    }

    @Test
    void verifyCode_noCodeForIdentifier_fails() {
        when(repository.findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider.EMAIL, EMAIL)).thenReturn(List.of());
        newService();

        boolean result = service.verifyCode(IdentityProvider.EMAIL, EMAIL, "123456");

        assertThat(result).isFalse();
    }

    private LoginCodeJpaEntity activeCode(IdentityProvider channel, String identifier, OffsetDateTime createdAt, int attempts) {
        var entity = new LoginCodeJpaEntity();
        entity.setChannel(channel);
        entity.setIdentifier(identifier);
        entity.setCodeHash(sha256("999999"));
        entity.setAttempts(attempts);
        entity.setCreatedAt(createdAt);
        entity.setExpiresAt(createdAt.plusMinutes(10));
        return entity;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
