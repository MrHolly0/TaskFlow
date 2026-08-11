package ru.taskflow.assistant.api.dto;

import ru.taskflow.assistant.api.ProposalStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Proposal(
        UUID id,
        String shortCode,
        UUID userId,
        ProposalStatus status,
        String sourceText,
        String clarification,
        List<ProposedAction> actions,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
) {
    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }

    public boolean isClarification() {
        return clarification != null && !clarification.isBlank();
    }
}
