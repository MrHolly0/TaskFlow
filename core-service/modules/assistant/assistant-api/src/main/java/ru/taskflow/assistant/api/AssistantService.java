package ru.taskflow.assistant.api;

import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;

import java.util.Optional;
import java.util.UUID;

public interface AssistantService {

    Proposal handleText(UUID userId, String text, AssistantChannel channel);

    Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel);

    Proposal findById(UUID userId, UUID proposalId);

    Proposal findByShortCode(UUID userId, String shortCode);

    /**
     * Последнее незакрытое предложение пользователя (status = PENDING, срок не
     * истёк), если оно есть — для быстрого пути подтверждения без обращения к
     * модели: FastPathResolver должен знать, есть ли контекст для «да»/«нет».
     */
    Optional<Proposal> findLatestPending(UUID userId);

    Proposal setActionAccepted(UUID userId, UUID proposalId, int ordinal, boolean accepted);

    ApplyResult apply(UUID userId, UUID proposalId);

    void reject(UUID userId, UUID proposalId);

    int transferOwnership(UUID from, UUID to);
}
