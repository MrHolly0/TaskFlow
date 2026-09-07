package ru.taskflow.assistant.api;

import java.time.OffsetDateTime;

public record PlannedDateGeneration(
        OffsetDateTime plannedDate,
        boolean noPlannedDateNeeded,
        int inputTokens,
        int outputTokens
) {}
