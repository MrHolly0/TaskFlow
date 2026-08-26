package ru.taskflow.task.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record ScheduleReminderRequest(
        @NotNull OffsetDateTime fireAt
) {}
