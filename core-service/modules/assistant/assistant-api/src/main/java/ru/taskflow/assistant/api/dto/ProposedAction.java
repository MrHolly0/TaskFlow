package ru.taskflow.assistant.api.dto;

import ru.taskflow.assistant.api.AssistantActionType;

import java.util.Map;
import java.util.UUID;

public record ProposedAction(
        int ordinal,
        AssistantActionType type,
        UUID targetTaskId,
        Map<String, Object> payload,
        String summary,
        boolean accepted
) {}
