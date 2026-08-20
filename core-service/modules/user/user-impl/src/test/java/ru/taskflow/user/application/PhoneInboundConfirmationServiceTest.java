package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneInboundConfirmationServiceTest {

    private static final String PHONE = "+79991234567";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private PhoneInboundConfirmationService service;

    private void newService() {
        service = new PhoneInboundConfirmationService(redis);
    }

    @Test
    void createPending_loginContext_storesUnderPhoneKeyWithEmptyUserId() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();

        service.createPending(PHONE, "79001000011", "103000", null);

        verify(valueOps).set(eq("phone-inbound:pending:" + PHONE), anyString(), any());
    }

    // Аудит переживает pending нарочно — на случай, если запись протухнет по
    // TTL, не получив подтверждения: logIfNaturallyExpired должен ещё застать
    // confirmationNumber/ucallerId, когда сам pending уже недоступен.
    @Test
    void createPending_alsoStoresAuditRecordForLaterExpiryLogging() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();

        service.createPending(PHONE, "79001000011", "103000", null);

        verify(valueOps).set(eq("phone-inbound:audit:" + PHONE), anyString(), any());
    }

    @Test
    void consumePending_matchingRoundTrip_returnsParsedPendingForLogin() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        service.createPending(PHONE, "79001000011", "103000", null);
        String stored = captureStoredValue();
        when(valueOps.getAndDelete("phone-inbound:pending:" + PHONE)).thenReturn(stored);

        var pending = service.consumePending(PHONE);

        assertThat(pending).isPresent();
        assertThat(pending.get().confirmationNumber()).isEqualTo("79001000011");
        assertThat(pending.get().ucallerId()).isEqualTo("103000");
        assertThat(pending.get().boundUserId()).isNull();
    }

    @Test
    void consumePending_bindContext_roundTripsBoundUserId() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        UUID userId = UUID.randomUUID();
        service.createPending(PHONE, "79001000011", "103000", userId);
        String stored = captureStoredValue();
        when(valueOps.getAndDelete("phone-inbound:pending:" + PHONE)).thenReturn(stored);

        var pending = service.consumePending(PHONE);

        assertThat(pending).isPresent();
        assertThat(pending.get().boundUserId()).isEqualTo(userId);
    }

    @Test
    void consumePending_nothingStored_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        when(valueOps.getAndDelete("phone-inbound:pending:" + PHONE)).thenReturn(null);

        assertThat(service.consumePending(PHONE)).isEmpty();
    }

    // Повторная доставка вебхука для того же callId у Ucaller несёт тот же
    // confirmationNumber — второй consumePending уже не находит запись,
    // потому что getAndDelete одноразовый. Здесь это смоделировано явно.
    @Test
    void consumePending_secondCallAfterFirstConsumed_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        service.createPending(PHONE, "79001000011", "103000", null);
        String stored = captureStoredValue();
        when(valueOps.getAndDelete("phone-inbound:pending:" + PHONE))
                .thenReturn(stored)
                .thenReturn(null);

        assertThat(service.consumePending(PHONE)).isPresent();
        assertThat(service.consumePending(PHONE)).isEmpty();
    }

    @Test
    void hasPending_delegatesToRedisHasKey() {
        when(redis.hasKey("phone-inbound:pending:" + PHONE)).thenReturn(true);
        newService();

        assertThat(service.hasPending(PHONE)).isTrue();
    }

    @Test
    void cancelPending_deletesPendingKey() {
        newService();

        service.cancelPending(PHONE);

        verify(redis).delete("phone-inbound:pending:" + PHONE);
    }

    // Отмена — по решению человека, не молчаливый срыв доставки: не должна
    // выглядеть в логах как истечение без подтверждения.
    @Test
    void cancelPending_alsoDeletesAuditKey() {
        newService();

        service.cancelPending(PHONE);

        verify(redis).delete("phone-inbound:audit:" + PHONE);
    }

    // Дошли до нас в любом виде — уже не "тихо не дозвонился": аудит должен
    // погаснуть вместе с pending, иначе logIfNaturallyExpired позже ошибочно
    // сочтёт успешно обработанный звонок молчаливым срывом доставки.
    @Test
    void consumePending_alsoDeletesAuditKeyRegardlessOfOutcome() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        when(valueOps.getAndDelete("phone-inbound:pending:" + PHONE)).thenReturn(null);

        service.consumePending(PHONE);

        verify(redis).delete("phone-inbound:audit:" + PHONE);
    }

    @Test
    void logIfNaturallyExpired_auditPresent_readsAndClearsIt() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        service.createPending(PHONE, "79001000011", "103000", null);
        String storedAudit = captureAuditValue();
        when(valueOps.getAndDelete("phone-inbound:audit:" + PHONE)).thenReturn(storedAudit);

        service.logIfNaturallyExpired(PHONE);

        verify(valueOps).getAndDelete("phone-inbound:audit:" + PHONE);
    }

    @Test
    void logIfNaturallyExpired_noAudit_doesNothing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        when(valueOps.getAndDelete("phone-inbound:audit:" + PHONE)).thenReturn(null);

        service.logIfNaturallyExpired(PHONE);
    }

    @Test
    void storeAndPollLoginResult_roundTrips() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        service.storeLoginResult(PHONE, "access-token", "refresh-token");
        String stored = captureStoredResultValue();
        when(valueOps.getAndDelete("phone-inbound:result:" + PHONE)).thenReturn(stored);

        var result = service.pollResult(PHONE);

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(PhoneInboundConfirmationService.LoginResult.class);
        var login = (PhoneInboundConfirmationService.LoginResult) result.get();
        assertThat(login.accessToken()).isEqualTo("access-token");
        assertThat(login.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void storeAndPollBindResult_roundTrips() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        OffsetDateTime verifiedAt = OffsetDateTime.parse("2026-08-20T12:00:00Z");
        service.storeBindResult(PHONE, verifiedAt);
        String stored = captureStoredResultValue();
        when(valueOps.getAndDelete("phone-inbound:result:" + PHONE)).thenReturn(stored);

        var result = service.pollResult(PHONE);

        assertThat(result).isPresent();
        var bind = (PhoneInboundConfirmationService.BindResult) result.get();
        assertThat(bind.verifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    void storeAndPollConflictResult_roundTrips() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        service.storeConflictResult(PHONE, 115, 12, 3, "merge-token-abc");
        String stored = captureStoredResultValue();
        when(valueOps.getAndDelete("phone-inbound:result:" + PHONE)).thenReturn(stored);

        var result = service.pollResult(PHONE);

        assertThat(result).isPresent();
        var conflict = (PhoneInboundConfirmationService.ConflictResult) result.get();
        assertThat(conflict.tasks()).isEqualTo(115);
        assertThat(conflict.groups()).isEqualTo(12);
        assertThat(conflict.tags()).isEqualTo(3);
        assertThat(conflict.mergeToken()).isEqualTo("merge-token-abc");
    }

    @Test
    void pollResult_nothingStored_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        when(valueOps.getAndDelete("phone-inbound:result:" + PHONE)).thenReturn(null);

        assertThat(service.pollResult(PHONE)).isEmpty();
    }

    @Test
    void pollResult_secondCallAfterFirstConsumed_returnsEmpty() {
        when(redis.opsForValue()).thenReturn(valueOps);
        newService();
        service.storeLoginResult(PHONE, "access-token", "refresh-token");
        String stored = captureStoredResultValue();
        when(valueOps.getAndDelete("phone-inbound:result:" + PHONE))
                .thenReturn(stored)
                .thenReturn(null);

        assertThat(service.pollResult(PHONE)).isPresent();
        assertThat(service.pollResult(PHONE)).isEmpty();
    }

    private final org.mockito.ArgumentCaptor<String> pendingCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
    private final org.mockito.ArgumentCaptor<String> resultCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
    private final org.mockito.ArgumentCaptor<String> auditCaptor = org.mockito.ArgumentCaptor.forClass(String.class);

    private String captureStoredValue() {
        verify(valueOps).set(eq("phone-inbound:pending:" + PHONE), pendingCaptor.capture(), any());
        return pendingCaptor.getValue();
    }

    private String captureStoredResultValue() {
        verify(valueOps).set(anyString(), resultCaptor.capture(), any());
        return resultCaptor.getValue();
    }

    private String captureAuditValue() {
        verify(valueOps).set(eq("phone-inbound:audit:" + PHONE), auditCaptor.capture(), any());
        return auditCaptor.getValue();
    }
}
