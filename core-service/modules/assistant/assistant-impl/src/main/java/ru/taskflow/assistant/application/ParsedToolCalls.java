package ru.taskflow.assistant.application;

import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.List;

public record ParsedToolCalls(
        List<ProposedAction> actions,
        List<String> rejections,
        String clarification,
        List<String> clarificationOptions,
        String searchQuery,
        boolean ambiguous,
        String ambiguityReason
) {
    // Совместимость со старыми вызовами: до mark_ambiguous двоякой трактовки не было.
    public ParsedToolCalls(List<ProposedAction> actions, List<String> rejections, String clarification,
                            List<String> clarificationOptions, String searchQuery) {
        this(actions, rejections, clarification, clarificationOptions, searchQuery, false, null);
    }

    public boolean isClarification() {
        return clarification != null && !clarification.isBlank();
    }

    public boolean needsSecondPass() {
        return searchQuery != null && !searchQuery.isBlank();
    }
}
