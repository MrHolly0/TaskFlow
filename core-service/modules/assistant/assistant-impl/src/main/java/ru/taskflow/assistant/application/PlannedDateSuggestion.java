package ru.taskflow.assistant.application;

import java.time.OffsetDateTime;

/**
 * Результат узкого вызова §4.3 — см. PlannedDateSuggester. plannedDate и
 * noPlannedDateNeeded взаимоисключающи, как planned_date/no_planned_date_needed
 * в payload действия; оба false/null означает, что модель не ответила
 * (истёк лимит, вызов инструмента не пришёл) — отличимо от осознанного отказа.
 */
public record PlannedDateSuggestion(
        OffsetDateTime plannedDate,
        boolean noPlannedDateNeeded,
        int inputTokens,
        int outputTokens
) {}
