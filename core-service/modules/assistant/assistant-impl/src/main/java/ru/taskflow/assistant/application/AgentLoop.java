package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.nlp.api.LlmMessage;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Собирает реплику пользователя в проверенный набор предложенных действий:
 * окно контекста → промпт → вызов модели → разбор → защита от дублей,
 * с возможным вторым проходом, если модель попросила поиск. В базу ничего
 * не пишет — сохранение предложений появится в следующей части.
 */
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final Duration BUDGET = Duration.ofSeconds(35);
    private static final String SEARCH_REF_PREFIX = "T";

    private final ContextBuilder contextBuilder;
    private final AssistantPromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final NlpGatewayService nlpGatewayService;
    private final ToolCallParser toolCallParser;
    private final DuplicateGuard duplicateGuard;
    private final TaskService taskService;
    private final Clock clock;

    public AgentOutcome run(UUID userId, String userText, ZoneId zone) {
        Instant start = clock.instant();

        TaskContextWindow window = contextBuilder.build(userId);
        PromptParts prompt = promptBuilder.build(window, userText, zone);
        List<Map<String, Object>> tools = toolRegistry.toolDefinitions();

        List<LlmMessage> historyPass1 = List.of(
                LlmMessage.system(prompt.systemPrompt()),
                LlmMessage.user(prompt.userMessage())
        );
        LlmToolResponse response1 = nlpGatewayService.callWithTools(new LlmToolRequest(historyPass1, tools));

        // разомкнутый автомат / исчерпаны попытки — ничего не спасаем из этого вызова,
        // реплику сохранит вызывающая сторона отдельным путём деградации (часть 2в)
        if (response1.failed()) {
            return new AgentOutcome(List.of(), List.of(), null, null, null, window, 1, true);
        }

        ParsedToolCalls parsed1 = toolCallParser.parse(toDomainCalls(response1.toolCalls()), window);
        DuplicateGuard.GuardResult guarded1 = duplicateGuard.filter(parsed1.actions(), window);

        List<String> rejections = new ArrayList<>(parsed1.rejections());
        rejections.addAll(guarded1.rejections());

        if (parsed1.isClarification()) {
            return new AgentOutcome(guarded1.actions(), rejections, parsed1.clarification(),
                    parsed1.clarificationOptions(), response1.text(), window, 1, false);
        }

        if (!parsed1.needsSecondPass() || budgetExceeded(start)) {
            return new AgentOutcome(guarded1.actions(), rejections, null, null,
                    response1.text(), window, 1, false);
        }

        return runSecondPass(userId, historyPass1, tools, response1, parsed1, guarded1, window, rejections);
    }

    private AgentOutcome runSecondPass(UUID userId, List<LlmMessage> historyPass1, List<Map<String, Object>> tools,
                                        LlmToolResponse response1, ParsedToolCalls parsed1,
                                        DuplicateGuard.GuardResult guarded1, TaskContextWindow window,
                                        List<String> rejections) {
        LlmToolCall searchCall = findSearchCall(response1.toolCalls());
        List<TaskResponse> found = taskService.search(userId, parsed1.searchQuery(), false, 20);
        ExtendedWindow extended = extendWindow(window, found);

        List<LlmMessage> historyPass2 = new ArrayList<>(historyPass1);
        historyPass2.add(LlmMessage.assistantToolCalls(List.of(searchCall)));
        historyPass2.add(LlmMessage.toolResult(
                searchCall.id(), ToolRegistry.SEARCH_TASKS, serializeFoundTasks(extended.newRefs(), found)));

        LlmToolResponse response2 = nlpGatewayService.callWithTools(new LlmToolRequest(historyPass2, tools));

        // модель уже вернула проверенные действия первого прохода — провал ОБОГАЩАЮЩЕГО
        // второго вызова не должен стирать то, что уже честно получено (принцип
        // «сказанное не теряется» из спеки); llmFailed=false, потому что первый вызов
        // реально удался, отказал только необязательный довесок
        if (response2.failed()) {
            return new AgentOutcome(guarded1.actions(), rejections, null, null,
                    response1.text(), extended.window(), 2, false);
        }

        ParsedToolCalls parsed2 = toolCallParser.parse(toDomainCalls(response2.toolCalls()), extended.window());
        DuplicateGuard.GuardResult guarded2 = duplicateGuard.filter(parsed2.actions(), extended.window());

        rejections.addAll(parsed2.rejections());
        rejections.addAll(guarded2.rejections());

        List<ProposedAction> combined = combineAndRenumber(guarded1.actions(), guarded2.actions());

        String clarification = parsed2.isClarification() ? parsed2.clarification() : null;
        List<String> clarificationOptions = parsed2.isClarification() ? parsed2.clarificationOptions() : null;
        String assistantText = isBlank(response2.text()) ? response1.text() : response2.text();

        return new AgentOutcome(combined, rejections, clarification, clarificationOptions,
                assistantText, extended.window(), 2, false);
    }

    private LlmToolCall findSearchCall(List<LlmToolCall> calls) {
        return calls.stream()
                .filter(call -> ToolRegistry.SEARCH_TASKS.equals(call.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "needsSecondPass() истинно без исходного вызова " + ToolRegistry.SEARCH_TASKS));
    }

    private boolean budgetExceeded(Instant start) {
        return Duration.between(start, clock.instant()).compareTo(BUDGET) >= 0;
    }

    private List<ToolCall> toDomainCalls(List<LlmToolCall> calls) {
        return calls.stream()
                .map(call -> new ToolCall(call.id(), call.name(), call.argumentsJson()))
                .toList();
    }

    private List<ProposedAction> combineAndRenumber(List<ProposedAction> firstPass, List<ProposedAction> secondPass) {
        List<ProposedAction> combined = new ArrayList<>(firstPass.size() + secondPass.size());
        combined.addAll(firstPass);
        combined.addAll(secondPass);

        List<ProposedAction> renumbered = new ArrayList<>(combined.size());
        int ordinal = 1;
        for (ProposedAction action : combined) {
            renumbered.add(new ProposedAction(ordinal++, action.type(), action.targetTaskId(),
                    action.payload(), action.summary(), action.accepted()));
        }
        return renumbered;
    }

    private record ExtendedWindow(TaskContextWindow window, List<String> newRefs) {}

    private ExtendedWindow extendWindow(TaskContextWindow original, List<TaskResponse> found) {
        Map<String, UUID> refs = new LinkedHashMap<>(original.refs());
        Map<String, String> titles = new LinkedHashMap<>(original.titles());
        StringBuilder rendered = new StringBuilder(original.rendered());
        List<String> newRefs = new ArrayList<>(found.size());

        int nextIndex = original.size() + 1;
        for (TaskResponse task : found) {
            String ref = SEARCH_REF_PREFIX + nextIndex++;
            refs.put(ref, task.id());
            titles.put(ref, task.title());
            if (!rendered.isEmpty()) {
                rendered.append('\n');
            }
            rendered.append(ref).append(" · ").append(task.title());
            newRefs.add(ref);
        }

        return new ExtendedWindow(new TaskContextWindow(rendered.toString(), Map.copyOf(refs), Map.copyOf(titles)), newRefs);
    }

    private String serializeFoundTasks(List<String> refs, List<TaskResponse> found) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < found.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"ref\":\"").append(refs.get(i)).append("\",\"title\":\"")
                    .append(escapeJson(found.get(i).title())).append("\"}");
        }
        return json.append(']').toString();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
