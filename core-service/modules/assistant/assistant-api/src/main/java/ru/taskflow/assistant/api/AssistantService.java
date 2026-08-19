package ru.taskflow.assistant.api;

import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;

import java.util.Optional;
import java.util.UUID;

public interface AssistantService {

    // Telegram не различает диалог и быстрое добавление — там всегда CHAT,
    // отсюда default-перегрузки без AssistantEntryPoint.
    default Proposal handleText(UUID userId, String text, AssistantChannel channel) {
        return handleText(userId, text, channel, AssistantEntryPoint.CHAT);
    }

    Proposal handleText(UUID userId, String text, AssistantChannel channel, AssistantEntryPoint entryPoint);

    default Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel) {
        return handleVoice(userId, audio, channel, AssistantEntryPoint.CHAT);
    }

    Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel, AssistantEntryPoint entryPoint);

    Proposal findById(UUID userId, UUID proposalId);

    Proposal findByShortCode(UUID userId, String shortCode);

    /**
     * Последнее незакрытое предложение пользователя (status = PENDING, срок не
     * истёк), если оно есть — для быстрого пути подтверждения без обращения к
     * модели: FastPathResolver должен знать, есть ли контекст для «да»/«нет».
     */
    Optional<Proposal> findLatestPending(UUID userId);

    Proposal setActionAccepted(UUID userId, UUID proposalId, int ordinal, boolean accepted);

    /**
     * Выбор одного варианта среди взаимоисключающих альтернатив: принимает
     * указанный ordinal, отклоняет все остальные действия предложения одним
     * атомарным вызовом — без гонки между двумя последовательными PATCH.
     */
    Proposal selectAlternative(UUID userId, UUID proposalId, int ordinal);

    ApplyResult apply(UUID userId, UUID proposalId);

    void reject(UUID userId, UUID proposalId);

    int transferOwnership(UUID from, UUID to);
}
