package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    public static final String PROPOSE_ACTIONS = "propose_actions";
    public static final String SEARCH_TASKS = "search_tasks";
    public static final String ASK_USER = "ask_user";
    public static final String NO_ACTION = "no_action";

    public boolean isProposeActions(String toolName) {
        return PROPOSE_ACTIONS.equals(toolName);
    }

    public boolean isRetrieval(String toolName) {
        return SEARCH_TASKS.equals(toolName);
    }

    public boolean isControl(String toolName) {
        return ASK_USER.equals(toolName);
    }

    public boolean isNoAction(String toolName) {
        return NO_ACTION.equals(toolName);
    }

    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                tool(PROPOSE_ACTIONS, "Единственный инструмент для предложения действий над задачами. "
                        + "Вызывается один раз на ответ: все действия, которые следуют из реплики — "
                        + "новые задачи, закрытия, переносы срока, изменения полей, отмены — "
                        + "перечисляются элементами списка actions, а не отдельными вызовами, даже если "
                        + "действие всего одно — оно всё равно единственный элемент списка.", Map.of(
                        "actions", Map.of(
                                "type", "array",
                                "description", "Действия, по одному объекту на каждое",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.ofEntries(
                                                Map.entry("type", enumParam("Вид действия",
                                                        List.of("create", "complete", "reschedule", "update", "cancel"))),
                                                Map.entry("task_ref", stringParam(
                                                        "Ярлык существующей задачи из списка, например T3 — "
                                                                + "для всех видов, кроме create")),
                                                Map.entry("title", stringParam(
                                                        "Название задачи — обязательно для create, "
                                                                + "новое название для update")),
                                                Map.entry("description", stringParam(
                                                        "Подробности — для create и update")),
                                                Map.entry("priority", enumParam("Приоритет — для create и update",
                                                        List.of("LOW", "MEDIUM", "HIGH", "URGENT"))),
                                                Map.entry("deadline", stringParam(
                                                        "Срок в формате ISO-8601 со смещением, например "
                                                                + "2026-08-12T18:00:00+03:00 — для create")),
                                                Map.entry("new_deadline", stringParam(
                                                        "Новый срок в формате ISO-8601 со смещением — для reschedule")),
                                                Map.entry("group", stringParam(
                                                        "Название группы одним-двумя словами на русском — "
                                                                + "для create и update")),
                                                Map.entry("tags", arrayParam("Метки — для create")),
                                                Map.entry("note", stringParam(
                                                        "Короткий комментарий, если пользователь его дал — для complete")),
                                                Map.entry("reason", stringParam(
                                                        "Причина отмены, если пользователь её назвал — для cancel")),
                                                Map.entry("ambiguous_reason", stringParam(
                                                        "Заполняй, только если это действие — одно из "
                                                                + "взаимоисключающих прочтений одной и той же фразы "
                                                                + "вместе с другим действием из этого же списка actions: "
                                                                + "коротко, что именно неоднозначно, для показа "
                                                                + "пользователю"))
                                        ),
                                        "required", List.of("type")
                                )
                        )
                ), List.of("actions")),

                tool(SEARCH_TASKS, "Найти задачи пользователя, если нужной нет в показанном списке", Map.of(
                        "query", stringParam("Поисковая фраза"),
                        "include_completed", Map.of("type", "boolean", "description", "Искать среди выполненных тоже")
                ), List.of("query")),

                tool(NO_ACTION, "Используй вместо propose_actions, когда реплика не описывает действие "
                        + "над задачами: вопрос о данных, реплика без содержания или формулировка, которую "
                        + "нельзя разобрать в команду. Заменяет действие, а не дополняет его — одного вызова "
                        + "достаточно.", Map.of(
                        "reason", enumParam("Почему действие не предлагается",
                                List.of("question", "chitchat", "unclear")),
                        "answer", stringParam("Короткий ответ пользователю")
                ), List.of("reason", "answer"))
        );
    }

    private Map<String, Object> tool(String name, String description,
                                     Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", required
                        )
                )
        );
    }

    private Map<String, Object> stringParam(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> enumParam(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }

    private Map<String, Object> arrayParam(String description) {
        return Map.of("type", "array", "description", description, "items", Map.of("type", "string"));
    }
}
