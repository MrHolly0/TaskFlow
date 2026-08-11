package ru.taskflow.assistant.api.exception;

import ru.taskflow.assistant.api.ProposalStatus;

import java.util.UUID;

public class ProposalNotPendingException extends RuntimeException {
    public ProposalNotPendingException(UUID proposalId, ProposalStatus status) {
        super("Предложение " + proposalId + " уже обработано, статус: " + status);
    }
}
