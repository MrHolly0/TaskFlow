package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Собирает реплику пользователя в проверенный набор предложенных действий:
 * окно контекста → промпт → вызов модели → разбор → защита от дублей,
 * с возможным вторым проходом, если модель попросила поиск. В базу ничего
 * не пишет — сохранение предложений появится в следующей части.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentLoop {

    private static final Duration BUDGET = Duration.ofSeconds(35);
    private static final String SEARCH_REF_PREFIX = "T";
    private static final int MAX_FALLBACK_TITLE_LENGTH = 512;
    private static final Pattern WORDS = Pattern.compile("\\s+");

    private final ContextBuilder contextBuilder;
    private final AssistantPromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final NlpGatewayService nlpGatewayService;
    private final ToolCallParser toolCallParser;
    private final DuplicateGuard duplicateGuard;
    private final TaskService taskService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

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
            return finishWithFallback(userText, guarded1.actions(), rejections, response1.text(), window, 1);
        }

        return runSecondPass(userId, userText, historyPass1, tools, response1, parsed1, guarded1, window, rejections);
    }

    private AgentOutcome runSecondPass(UUID userId, String userText, List<LlmMessage> historyPass1, List<Map<String, Object>> tools,
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
        DuplicateGuard.GuardResult guarded2 = duplicateGuard.filter(parsed2.actions(), extended.window(), guarded1.actions());

        rejections.addAll(parsed2.rejections());
        rejections.addAll(guarded2.rejections());

        List<ProposedAction> combined = combineAndRenumber(guarded1.actions(), guarded2.actions());

        String clarification = parsed2.isClarification() ? parsed2.clarification() : null;
        List<String> clarificationOptions = parsed2.isClarification() ? parsed2.clarificationOptions() : null;
        String assistantText = isBlank(response2.text()) ? response1.text() : response2.text();

        if (clarification != null) {
            return new AgentOutcome(combined, rejections, clarification, clarificationOptions,
                    assistantText, extended.window(), 2, false);
        }

        return finishWithFallback(userText, combined, rejections, assistantText, extended.window(), 2);
    }

    private AgentOutcome finishWithFallback(String userText, List<ProposedAction> actions, List<String> rejections,
                                            String assistantText, TaskContextWindow window, int passes) {
        // Пустой actions() бывает по двум причинам: модель ничего не предложила,
        // либо предложила, но фильтры (DuplicateGuard и другие) отбросили. Запасной
        // путь имеет смысл только в первом случае — во втором решение фильтров уже
        // принято, и подменять его сырой репликой нельзя (см. Task 0 части 3б).
        boolean modelSaidNothing = actions.isEmpty() && rejections.isEmpty();
        if (!modelSaidNothing || !looksLikeStandaloneTask(userText)) {
            return new AgentOutcome(actions, rejections, null, null, assistantText, window, passes, false);
        }

        DuplicateGuard.GuardResult guarded = duplicateGuard.filter(List.of(fallbackCreateAction(userText)), window);
        List<String> allRejections = new ArrayList<>(rejections);
        allRejections.addAll(guarded.rejections());
        return new AgentOutcome(guarded.actions(), allRejections, null, null, assistantText, window, passes, false);
    }

    private ProposedAction fallbackCreateAction(String userText) {
        String title = normalizeTitle(userText);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        return new ProposedAction(1, AssistantActionType.CREATE, null, payload,
                truncate("Создать — " + title), true);
    }

    private boolean looksLikeStandaloneTask(String userText) {
        String title = normalizeTitle(userText);
        if (title.length() < 8 || title.length() > MAX_FALLBACK_TITLE_LENGTH || title.endsWith("?")) {
            return false;
        }

        String[] words = WORDS.split(title);
        if (words.length < 2) {
            return false;
        }

        String lower = title.toLowerCase(Locale.ROOT);
        return !startsWithAny(lower,
                "найди", "найти", "покажи", "показать",
                "закрой", "закрыть", "заверши", "завершить",
                "отмени", "отменить", "перенеси", "перенести", "сдвинь",
                "измени", "изменить", "удали", "удалить", "очисти", "очистить",
                "что ", "как ", "почему ", "где ", "когда ", "сколько ",
                "привет", "спасибо", "ничего");
    }

    private boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeTitle(String userText) {
        return WORDS.matcher(userText.trim()).replaceAll(" ");
    }

    private String truncate(String value) {
        return value.length() <= 256 ? value : value.substring(0, 255) + "…";
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
        List<Map<String, String>> payload = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            payload.add(Map.of("ref", refs.get(i), "title", found.get(i).title()));
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Не удалось сериализовать результаты поиска, вернём пустой список", e);
            return "[]";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
