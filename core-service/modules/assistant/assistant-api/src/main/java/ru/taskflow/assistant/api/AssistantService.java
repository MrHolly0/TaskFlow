package ru.taskflow.assistant.api;

import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;

import java.util.UUID;

public interface AssistantService {

    Proposal handleText(UUID userId, String text, AssistantChannel channel);

    Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel);

    Proposal findById(UUID userId, UUID proposalId);

    Proposal findByShortCode(UUID userId, String shortCode);

    Proposal setActionAccepted(UUID userId, UUID proposalId, int ordinal, boolean accepted);

    ApplyResult apply(UUID userId, UUID proposalId);

    void reject(UUID userId, UUID proposalId);
}
