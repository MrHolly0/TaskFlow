package ru.taskflow.shared.exception;

import java.util.UUID;

/**
 * Идентификатор (email/telegram id) уже привязан к другой учётке. Не ошибка
 * в обычном смысле — сигнал для переноса данных, поэтому несёт ID владельца.
 */
public class IdentityConflictException extends RuntimeException {

    private final UUID conflictingUserId;

    public IdentityConflictException(String message, UUID conflictingUserId) {
        super(message);
        this.conflictingUserId = conflictingUserId;
    }

    public UUID getConflictingUserId() {
        return conflictingUserId;
    }
}
