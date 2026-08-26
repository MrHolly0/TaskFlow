package ru.taskflow.task.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReminderResponse(UUID id, OffsetDateTime fireAt, String status) {}
