package ru.taskflow.assistant.api.exception;

import ru.taskflow.shared.exception.NotFoundException;

import java.util.UUID;

public class ProposalNotFoundException extends NotFoundException {
    public ProposalNotFoundException(UUID proposalId) {
        super("предложение не найдено: " + proposalId);
    }
}
