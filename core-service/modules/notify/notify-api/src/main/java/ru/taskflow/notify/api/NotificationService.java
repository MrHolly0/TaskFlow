package ru.taskflow.notify.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NotificationService {
    void scheduleTaskReminder(UUID userId, UUID taskId, String title, OffsetDateTime deadline);
    void cancelTaskNotifications(UUID taskId);

    /**
     * Переносит запланированные напоминания на другую учётку. Адресат каждой
     * строки пересчитывается на идентичность to того же канала, если она
     * есть — иначе остаётся прежним (столбец NOT NULL, придумывать значение
     * нельзя).
     */
    int transferOwnership(UUID from, UUID to);
}
