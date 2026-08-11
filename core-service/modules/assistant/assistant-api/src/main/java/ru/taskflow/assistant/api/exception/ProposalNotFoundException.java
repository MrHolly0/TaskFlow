package ru.taskflow.assistant.api.exception;

import java.util.UUID;

public class ProposalNotFoundException extends RuntimeException {
    public ProposalNotFoundException(UUID proposalId) {
        super("Предложение не найдено: " + proposalId);
    }
}
