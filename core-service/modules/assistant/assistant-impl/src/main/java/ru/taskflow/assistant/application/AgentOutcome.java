package ru.taskflow.assistant.application;

import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.List;

/**
 * totalLatencyMs/firstPassLatencyMs/secondPassLatencyMs — время всего run(), первого и
 * второго прохода модели соответственно; secondPassLatencyMs остаётся 0, если второго
 * прохода не было. Оба выставляются один раз, в AgentLoop.run(), поверх результата
 * внутренних веток — см. withLatencies/withSecondPassLatency: остальной код outcome не
 * знает и знать не должен, RTT замеряется снаружи по месту вызова модели.
 * declineReason — не null, только если модель вызвала no_action вместо
 * propose_actions (Б1); в этом случае assistantText несёт её ответ пользователю.
 */
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
        int outputTokens,
        long totalLatencyMs,
        long firstPassLatencyMs,
        long secondPassLatencyMs,
        DeclineReason declineReason
) {
    // Совместимость со старыми вызовами: до mark_ambiguous двоякой трактовки не было,
    // до починки учёта токенов и задержек по этапам — этих полей тоже не было.
    // Используется только там, где обращение к модели не состоялось (llmFailed) —
    // расход и задержки в этом случае и должны быть нулевыми на момент создания;
    // totalLatencyMs всё равно проставляется поверх в run().
    public AgentOutcome(List<ProposedAction> actions, List<String> rejections, String clarification,
                         List<String> clarificationOptions, String assistantText, TaskContextWindow window,
                         int passes, boolean llmFailed) {
        this(actions, rejections, clarification, clarificationOptions, assistantText, window, passes, llmFailed,
                false, null, 0, 0, 0, 0, 0, null);
    }

    // Промежуточная совместимость: расход токенов уже известен веткой AgentLoop,
    // задержки — ещё нет, их проставляет withLatencies/withSecondPassLatency поверх.
    public AgentOutcome(List<ProposedAction> actions, List<String> rejections, String clarification,
                         List<String> clarificationOptions, String assistantText, TaskContextWindow window,
                         int passes, boolean llmFailed, boolean ambiguous, String ambiguityReason,
                         int inputTokens, int outputTokens) {
        this(actions, rejections, clarification, clarificationOptions, assistantText, window, passes, llmFailed,
                ambiguous, ambiguityReason, inputTokens, outputTokens, 0, 0, 0, null);
    }

    // Совместимость: до no_action отдельного отказа от действия не было.
    public AgentOutcome(List<ProposedAction> actions, List<String> rejections, String clarification,
                         List<String> clarificationOptions, String assistantText, TaskContextWindow window,
                         int passes, boolean llmFailed, boolean ambiguous, String ambiguityReason,
                         int inputTokens, int outputTokens, long totalLatencyMs, long firstPassLatencyMs,
                         long secondPassLatencyMs) {
        this(actions, rejections, clarification, clarificationOptions, assistantText, window, passes, llmFailed,
                ambiguous, ambiguityReason, inputTokens, outputTokens, totalLatencyMs, firstPassLatencyMs,
                secondPassLatencyMs, null);
    }

    public boolean isDeclined() {
        return declineReason != null;
    }
}
