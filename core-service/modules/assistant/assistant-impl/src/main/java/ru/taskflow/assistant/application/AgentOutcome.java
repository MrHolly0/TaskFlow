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
        boolean llmFailed,
        boolean ambiguous,
        String ambiguityReason,
        int inputTokens,
        int outputTokens
) {
    // Совместимость со старыми вызовами: до mark_ambiguous двоякой трактовки не было,
    // до починки учёта токенов (см. LlmToolResponse.inputTokens/outputTokens) — полей
    // расхода тоже не было. Используется только там, где обращение к модели не
    // состоялось (llmFailed) — расход в этом случае и должен быть нулевым.
    public AgentOutcome(List<ProposedAction> actions, List<String> rejections, String clarification,
                         List<String> clarificationOptions, String assistantText, TaskContextWindow window,
                         int passes, boolean llmFailed) {
        this(actions, rejections, clarification, clarificationOptions, assistantText, window, passes, llmFailed,
                false, null, 0, 0);
    }
}
