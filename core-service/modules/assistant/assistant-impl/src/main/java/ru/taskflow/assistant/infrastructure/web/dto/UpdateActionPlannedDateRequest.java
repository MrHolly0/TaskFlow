package ru.taskflow.assistant.infrastructure.web.dto;

import java.time.OffsetDateTime;

public record UpdateActionPlannedDateRequest(OffsetDateTime plannedDate) {
}
