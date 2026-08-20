package ru.taskflow.task.api.dto;

import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;

public record TaskStatsItem(
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        OffsetDateTime deadline,
        TaskStatus status
) {}
