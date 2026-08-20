package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.AssistantEntryPoint;
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
    private final TitleChangeGuard titleChangeGuard;
    private final TaskService taskService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgentOutcome run(UUID userId, String userText, ZoneId zone) {
        return run(userId, userText, zone, AssistantEntryPoint.CHAT);
    }

    public AgentOutcome run(UUID userId, String userText, ZoneId zone, AssistantEntryPoint entryPoint) {
        Instant start = clock.instant();

        TaskContextWindow window = contextBuilder.build(userId);
        List<String> groupNames = taskService.findGroupNames(userId);
        PromptParts prompt = promptBuilder.build(window, userText, zone, entryPoint, groupNames);
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
        TitleChangeGuard.GuardResult titled1 = titleChangeGuard.filter(guarded1.actions(), window, userText);

        List<String> rejections = new ArrayList<>(parsed1.rejections());
        rejections.addAll(guarded1.rejections());
        rejections.addAll(titled1.rejections());

        if (parsed1.isClarification() || parsed1.ambiguous()) {
            return new AgentOutcome(titled1.actions(), rejections, parsed1.clarification(),
                    parsed1.clarificationOptions(), response1.text(), window, 1, false,
                    parsed1.ambiguous(), parsed1.ambiguityReason());
        }

        if (!parsed1.needsSecondPass() || budgetExceeded(start)) {
            return finishWithFallback(userText, titled1.actions(), rejections, response1.text(), window, 1,
                    entryPoint, parsed1.rejectedTarget());
        }

        return runSecondPass(userId, userText, historyPass1, tools, response1, parsed1, titled1.actions(), window, rejections, entryPoint);
    }

    private AgentOutcome runSecondPass(UUID userId, String userText, List<LlmMessage> historyPass1, List<Map<String, Object>> tools,
                                        LlmToolResponse response1, ParsedToolCalls parsed1,
                                        List<ProposedAction> pass1Actions, TaskContextWindow window,
                                        List<String> rejections, AssistantEntryPoint entryPoint) {
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
            return new AgentOutcome(pass1Actions, rejections, null, null,
                    response1.text(), extended.window(), 2, false);
        }

        ParsedToolCalls parsed2 = toolCallParser.parse(toDomainCalls(response2.toolCalls()), extended.window());
        DuplicateGuard.GuardResult guarded2 = duplicateGuard.filter(parsed2.actions(), extended.window(), pass1Actions);
        TitleChangeGuard.GuardResult titled2 = titleChangeGuard.filter(guarded2.actions(), extended.window(), userText);

        rejections.addAll(parsed2.rejections());
        rejections.addAll(guarded2.rejections());
        rejections.addAll(titled2.rejections());

        List<ProposedAction> combined = combineAndRenumber(pass1Actions, titled2.actions());

        String clarification = parsed2.isClarification() ? parsed2.clarification() : null;
        List<String> clarificationOptions = parsed2.isClarification() ? parsed2.clarificationOptions() : null;
        String assistantText = isBlank(response2.text()) ? response1.text() : response2.text();

        if (clarification != null || parsed2.ambiguous()) {
            return new AgentOutcome(combined, rejections, clarification, clarificationOptions,
                    assistantText, extended.window(), 2, false, parsed2.ambiguous(), parsed2.ambiguityReason());
        }

        UUID rejectedTarget = parsed2.rejectedTarget() != null ? parsed2.rejectedTarget() : parsed1.rejectedTarget();
        return finishWithFallback(userText, combined, rejections, assistantText, extended.window(), 2,
                entryPoint, rejectedTarget);
    }

    /**
     * Двоякость — не особый случай, а результат разбора: реплика читается как
     * название задачи (структура) и при этом либо модель промолчала, либо
     * предложила действие над задачей, с которой реплика не совпадает почти
     * дословно (мера DuplicateGuard, его порог). Совпадает — это дубль, им уже
     * занимается DuplicateGuard; не совпадает — оба прочтения правдоподобны.
     */
    private AgentOutcome finishWithFallback(String userText, List<ProposedAction> actions, List<String> rejections,
                                            String assistantText, TaskContextWindow window, int passes,
                                            AssistantEntryPoint entryPoint, UUID rejectedTarget) {
        // Пустой actions() бывает по четырём причинам: модель ничего не
        // предложила, предложила — но фильтры отбросили (Task 0 части 3б,
        // чужое решение не подменяем), ярлык разрешился, а дальше
        // ActionValidator отклонил остальное (нечего менять, не разобрался
        // срок — rejectedTarget не null, это не «фильтр отбросил
        // осмысленное», а то же самое молчание с понятной задачей), или
        // модель сходила в поиск и не нашла что предложить (passes == 2,
        // действий нет, отказов нет — это запрос к данным, а не название
        // новой задачи: «покажи задачи на завтра» точно так же не читалась
        // бы отдельно от контекста поиска, если бы поиска не было).
        boolean searchedAndFoundNothingToPropose = passes == 2 && actions.isEmpty() && rejections.isEmpty();
        boolean modelSaidNothing = actions.isEmpty() && (rejections.isEmpty() || rejectedTarget != null)
                && !searchedAndFoundNothingToPropose;
        boolean readsLikeATaskName = looksLikeStandaloneTask(userText);

        if (modelSaidNothing && readsLikeATaskName) {
            DuplicateGuard.GuardResult guarded = duplicateGuard.filter(List.of(fallbackCreateAction(userText)), window);
            List<String> allRejections = new ArrayList<>(rejections);
            allRejections.addAll(guarded.rejections());
            return new AgentOutcome(guarded.actions(), allRejections, null, null, assistantText, window, passes, false);
        }

        if (readsLikeATaskName && actions.size() == 1 && actions.getFirst().targetTaskId() != null) {
            ProposedAction existing = actions.getFirst();
            String targetTitle = window.titleFor(existing.targetTaskId());
            if (!duplicateGuard.isDuplicateOf(userText, targetTitle)) {
                List<ProposedAction> alternatives = orderAlternatives(existing, fallbackCreateAction(userText), entryPoint);
                String reason = "реплика могла означать «" + existing.summary() + "», а могла — новую задачу";
                return new AgentOutcome(alternatives, rejections, null, null, assistantText, window, passes, false,
                        true, reason);
            }
        }

        return new AgentOutcome(actions, rejections, null, null, assistantText, window, passes, false);
    }

    /**
     * Из быстрого добавления создание идёт первым вариантом (там чаще хотят
     * добавить новое), из чата — первым остаётся то, что предложила модель.
     * Значение по умолчанию, не запрет: применяется только к порядку показа
     * и к тому, какой вариант выбран изначально.
     */
    private List<ProposedAction> orderAlternatives(ProposedAction existing, ProposedAction create, AssistantEntryPoint entryPoint) {
        List<ProposedAction> ordered = entryPoint == AssistantEntryPoint.QUICK_ADD
                ? List.of(create, existing)
                : List.of(existing, create);
        List<ProposedAction> renumbered = new ArrayList<>(2);
        for (int i = 0; i < ordered.size(); i++) {
            ProposedAction a = ordered.get(i);
            renumbered.add(new ProposedAction(i + 1, a.type(), a.targetTaskId(), a.payload(), a.summary(), i == 0));
        }
        return renumbered;
    }

    private ProposedAction fallbackCreateAction(String userText) {
        String title = normalizeTitle(userText);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        return new ProposedAction(1, AssistantActionType.CREATE, null, payload,
                truncate("Создать — " + title), true);
    }

    // Только структура — длина, число слов, не вопрос. Ни одного глагола:
    // открытый класс форм и синонимов такой список всё равно не покрыл бы
    // (см. историю в плане Task 5, вторая редакция).
    private boolean looksLikeStandaloneTask(String userText) {
        String title = normalizeTitle(userText);
        if (title.length() < 8 || title.length() > MAX_FALLBACK_TITLE_LENGTH || title.endsWith("?")) {
            return false;
        }

        String[] words = WORDS.split(title);
        return words.length >= 2;
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
