package ru.taskflow.task.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReminderResponse(UUID id, OffsetDateTime fireAt, String status, boolean persistent) {
    // Совместимость: до Б2 (настойчивость) цепочки не было вовсе.
    public ReminderResponse(UUID id, OffsetDateTime fireAt, String status) {
        this(id, fireAt, status, false);
    }
}
