package ru.taskflow.assistant.application;

import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.List;
import java.util.UUID;

/**
 * rejectedTarget — задача, на которую указывал единственный отклонённый
 * ActionValidator вызов (ярлык разрешился, но остальное не собралось —
 * «нечего менять», неразобранный срок). Двоякость в AgentLoop должна знать
 * об этой задаче так же, как если бы действие прошло валидацию: отказ
 * ActionValidator — не то же самое, что модель ничего не сказала о задаче.
 */
public record ParsedToolCalls(
        List<ProposedAction> actions,
        List<String> rejections,
        String clarification,
        List<String> clarificationOptions,
        String searchQuery,
        boolean ambiguous,
        String ambiguityReason,
        UUID rejectedTarget,
        DeclineReason declineReason,
        String declineAnswer
) {
    // Совместимость со старыми вызовами: до mark_ambiguous двоякой трактовки не было.
    public ParsedToolCalls(List<ProposedAction> actions, List<String> rejections, String clarification,
                            List<String> clarificationOptions, String searchQuery) {
        this(actions, rejections, clarification, clarificationOptions, searchQuery, false, null, null, null, null);
    }

    // Совместимость: до no_action отказа отдельно от двоякости не было.
    public ParsedToolCalls(List<ProposedAction> actions, List<String> rejections, String clarification,
                            List<String> clarificationOptions, String searchQuery, boolean ambiguous,
                            String ambiguityReason, UUID rejectedTarget) {
        this(actions, rejections, clarification, clarificationOptions, searchQuery, ambiguous, ambiguityReason,
                rejectedTarget, null, null);
    }

    public boolean isClarification() {
        return clarification != null && !clarification.isBlank();
    }

    public boolean needsSecondPass() {
        return searchQuery != null && !searchQuery.isBlank();
    }

    public boolean isDeclined() {
        return declineReason != null;
    }
}
