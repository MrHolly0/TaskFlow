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
        OffsetDateTime expiresAt,
        boolean exclusive,
        String ambiguityReason,
        List<String> rejections
) {
    // Совместимость со старыми вызовами: до mark_ambiguous предложение не могло
    // быть взаимоисключающим набором альтернатив.
    public Proposal(UUID id, String shortCode, UUID userId, ProposalStatus status, String sourceText,
                     String clarification, List<ProposedAction> actions, OffsetDateTime createdAt,
                     OffsetDateTime expiresAt) {
        this(id, shortCode, userId, status, sourceText, clarification, actions, createdAt, expiresAt, false, null,
                List.of());
    }

    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }

    public boolean hasRejections() {
        return rejections != null && !rejections.isEmpty();
    }

    public boolean isClarification() {
        return clarification != null && !clarification.isBlank();
    }
}
