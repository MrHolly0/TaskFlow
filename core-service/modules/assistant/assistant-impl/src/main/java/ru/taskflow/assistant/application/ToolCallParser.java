package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Единственное место, где сырые вызовы инструментов от модели превращаются
 * в проверенные предложенные действия. Нормализация мусорных значений
 * выполняется до валидации: модель присылает "null" строкой вместо
 * отсутствующего ключа.
 */
@Component
@RequiredArgsConstructor
public class ToolCallParser {

    private static final int MAX_ACTIONS = 20;
    private static final Set<AssistantActionType> REF_BEARING_TYPES = EnumSet.of(
            AssistantActionType.COMPLETE, AssistantActionType.RESCHEDULE,
            AssistantActionType.UPDATE, AssistantActionType.CANCEL, AssistantActionType.REMIND
    );

    private final ToolRegistry toolRegistry;
    private final ActionValidator actionValidator;
    private final SummaryRenderer summaryRenderer;
    private final ObjectMapper objectMapper;

    public ParsedToolCalls parse(List<ToolCall> calls, TaskContextWindow window) {
        List<ProposedAction> actions = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        String clarification = null;
        List<String> clarificationOptions = null;
        String searchQuery = null;
        boolean ambiguous = false;
        String ambiguityReason = null;
        UUID rejectedTarget = null;
        DeclineReason declineReason = null;
        String declineAnswer = null;

        for (ToolCall call : calls) {
            Map<String, Object> args;
            try {
                args = normalize(parseArguments(call.argumentsJson()));
            } catch (Exception e) {
                rejections.add("не удалось разобрать аргументы вызова " + call.name());
                continue;
            }

            if (toolRegistry.isProposeActions(call.name())) {
                List<Map<String, Object>> items = listOfMapsArg(args, "actions");
                if (items.isEmpty()) {
                    rejections.add(call.name() + ": пустой список actions");
                    continue;
                }
                for (Map<String, Object> rawItemArgs : items) {
                    Map<String, Object> itemArgs = normalize(rawItemArgs);
                    AssistantActionType itemType = parseActionType(itemArgs.get("type"));
                    if (itemType == null) {
                        rejections.add(call.name() + ": неизвестный тип действия: " + itemArgs.get("type"));
                        continue;
                    }

                    var validation = actionValidator.validate(itemType, itemArgs, window);
                    if (!validation.valid()) {
                        rejections.add(call.name() + ": " + validation.error());
                        if (rejectedTarget == null && validation.targetTaskId() != null) {
                            rejectedTarget = validation.targetTaskId();
                        }
                        continue;
                    }
                    if (actions.size() >= MAX_ACTIONS) {
                        rejections.add("превышен лимит действий: " + call.name());
                        continue;
                    }

                    String taskTitle = REF_BEARING_TYPES.contains(itemType)
                            ? window.title(refOf(itemArgs))
                            : null;
                    String summary = summaryRenderer.render(itemType, taskTitle, itemArgs);
                    actions.add(new ProposedAction(
                            actions.size() + 1,
                            itemType,
                            validation.targetTaskId(),
                            itemArgs,
                            summary,
                            true
                    ));

                    // Признак двоякости — поле того же элемента списка actions, а не
                    // отдельный инструмент: только так модель может сообщить о ней
                    // вместе с самими действиями, за один вызов (mark_ambiguous не
                    // мог сработать вместе с действием по той же причине, по которой
                    // не работал пакет — модель не делает два вызова инструмента).
                    String itemAmbiguousReason = stringArg(itemArgs, "ambiguous_reason");
                    if (itemAmbiguousReason != null && !itemAmbiguousReason.isBlank()) {
                        ambiguous = true;
                        if (ambiguityReason == null) {
                            ambiguityReason = itemAmbiguousReason;
                        }
                    }
                }
                continue;
            }

            if (toolRegistry.isRetrieval(call.name())) {
                if (searchQuery != null) {
                    rejections.add("повторный поиск отклонён: " + call.name());
                    continue;
                }
                searchQuery = stringArg(args, "query");
                continue;
            }

            if (toolRegistry.isControl(call.name())) {
                if (clarification != null) {
                    rejections.add("повторный уточняющий вопрос отклонён: " + call.name());
                    continue;
                }
                clarification = stringArg(args, "question");
                clarificationOptions = listArg(args, "options");
                continue;
            }

            if (toolRegistry.isNoAction(call.name())) {
                if (declineReason != null) {
                    rejections.add("повторный отказ отклонён: " + call.name());
                    continue;
                }
                DeclineReason parsedReason = parseDeclineReason(stringArg(args, "reason"));
                if (parsedReason == null) {
                    rejections.add(call.name() + ": неизвестная причина отказа: " + args.get("reason"));
                    continue;
                }
                declineReason = parsedReason;
                declineAnswer = stringArg(args, "answer");
                continue;
            }

            rejections.add("неизвестный инструмент: " + call.name());
        }

        if (ambiguous) {
            actions = onlyFirstAccepted(actions);
        }

        return new ParsedToolCalls(actions, rejections, clarification, clarificationOptions, searchQuery,
                ambiguous, ambiguityReason, rejectedTarget, declineReason, declineAnswer);
    }

    // Ровно один вариант выбран по умолчанию — переключатели в интерфейсе,
    // а не флажки, где всё принятое применяется сразу.
    private List<ProposedAction> onlyFirstAccepted(List<ProposedAction> actions) {
        List<ProposedAction> adjusted = new ArrayList<>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            ProposedAction a = actions.get(i);
            adjusted.add(new ProposedAction(a.ordinal(), a.type(), a.targetTaskId(), a.payload(), a.summary(), i == 0));
        }
        return adjusted;
    }

    private AssistantActionType parseActionType(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return AssistantActionType.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Закрытый список (Б1): свободный текст здесь не превращает провалы
    // NEGATIVE в измеримые доли, только фиксированные значения.
    private DeclineReason parseDeclineReason(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return DeclineReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String refOf(Map<String, Object> args) {
        Object ref = args.get("task_ref");
        return ref == null ? null : ref.toString();
    }

    private Map<String, Object> parseArguments(String argumentsJson) throws Exception {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new HashMap<>();
        }
        return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> normalize(Map<String, Object> args) {
        Map<String, Object> normalized = new HashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            if (isGarbage(entry.getValue())) {
                continue;
            }
            normalized.put(entry.getKey(), entry.getValue());
        }
        return normalized;
    }

    private boolean isGarbage(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() || trimmed.equalsIgnoreCase("null");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMapsArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private List<String> listArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : value.toString();
    }
}
