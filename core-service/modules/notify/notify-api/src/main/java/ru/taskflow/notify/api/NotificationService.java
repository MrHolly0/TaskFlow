package ru.taskflow.notify.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface NotificationService {
    /**
     * urgent — приоритет задачи URGENT: при включённом в настройках
     * urgentExtraReminder планирует дополнительное напоминание за 15 минут
     * до срока, в дополнение к обычному.
     */
    void scheduleTaskReminder(UUID userId, UUID taskId, String title, OffsetDateTime deadline, boolean urgent);
    void cancelTaskNotifications(UUID taskId);

    /**
     * Переносит запланированные напоминания на другую учётку. Адресат каждой
     * строки пересчитывается на идентичность to того же канала, если она
     * есть — иначе остаётся прежним (столбец NOT NULL, придумывать значение
     * нельзя).
     */
    int transferOwnership(UUID from, UUID to);
}
