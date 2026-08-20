package ru.taskflow.user.infrastructure.web.dto;

/**
 * Ответ на опрос состояния входящего подтверждения при входе. status —
 * "waiting" (ждём звонка), "confirmed" (вошли, токены в ответе) или
 * "expired" (пять минут истекли, начинать заново).
 */
public record PhoneConfirmationStatusResponse(String status, String token, String refreshToken) {

    public static PhoneConfirmationStatusResponse waiting() {
        return new PhoneConfirmationStatusResponse("waiting", null, null);
    }

    public static PhoneConfirmationStatusResponse expired() {
        return new PhoneConfirmationStatusResponse("expired", null, null);
    }

    public static PhoneConfirmationStatusResponse confirmed(String token, String refreshToken) {
        return new PhoneConfirmationStatusResponse("confirmed", token, refreshToken);
    }
}
