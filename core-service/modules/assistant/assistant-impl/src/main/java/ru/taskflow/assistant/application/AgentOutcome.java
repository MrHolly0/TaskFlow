package ru.taskflow.assistant.application;

import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.List;

public record AgentOutcome(
        List<ProposedAction> actions,
        List<String> rejections,
        String clarification,
        List<String> clarificationOptions,
        String assistantText,
        TaskContextWindow window,
        int passes,
        boolean llmFailed
) {}
