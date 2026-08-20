package ru.taskflow.app.web.dto;

import ru.taskflow.user.api.dto.IdentityDto;

/**
 * Ответ на опрос состояния входящего подтверждения при привязке. status —
 * "waiting", "confirmed" (identity заполнен), "conflict" (номер уже у другой
 * учётки — tasks/groups/tags/mergeToken заполнены, как в 409 у /email/confirm)
 * или "expired".
 */
public record PhoneBindConfirmationStatusResponse(
        String status,
        IdentityDto identity,
        Integer tasks,
        Integer groups,
        Integer tags,
        String mergeToken
) {

    public static PhoneBindConfirmationStatusResponse waiting() {
        return new PhoneBindConfirmationStatusResponse("waiting", null, null, null, null, null);
    }

    public static PhoneBindConfirmationStatusResponse expired() {
        return new PhoneBindConfirmationStatusResponse("expired", null, null, null, null, null);
    }

    public static PhoneBindConfirmationStatusResponse confirmed(IdentityDto identity) {
        return new PhoneBindConfirmationStatusResponse("confirmed", identity, null, null, null, null);
    }

    public static PhoneBindConfirmationStatusResponse conflict(int tasks, int groups, int tags, String mergeToken) {
        return new PhoneBindConfirmationStatusResponse("conflict", null, tasks, groups, tags, mergeToken);
    }
}
