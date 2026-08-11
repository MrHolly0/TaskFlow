package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;

import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    public static final String CREATE_TASK = "create_task";
    public static final String COMPLETE_TASK = "complete_task";
    public static final String RESCHEDULE_TASK = "reschedule_task";
    public static final String UPDATE_TASK = "update_task";
    public static final String CANCEL_TASK = "cancel_task";
    public static final String SEARCH_TASKS = "search_tasks";
    public static final String ASK_USER = "ask_user";

    private static final Map<String, AssistantActionType> ACTION_TYPES = Map.of(
            CREATE_TASK, AssistantActionType.CREATE,
            COMPLETE_TASK, AssistantActionType.COMPLETE,
            RESCHEDULE_TASK, AssistantActionType.RESCHEDULE,
            UPDATE_TASK, AssistantActionType.UPDATE,
            CANCEL_TASK, AssistantActionType.CANCEL
    );

    public AssistantActionType actionTypeOf(String toolName) {
        return ACTION_TYPES.get(toolName);
    }

    public boolean isRetrieval(String toolName) {
        return SEARCH_TASKS.equals(toolName);
    }

    public boolean isControl(String toolName) {
        return ASK_USER.equals(toolName);
    }

    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                tool(CREATE_TASK, "Создать новую задачу", Map.of(
                        "title", stringParam("Короткое название задачи"),
                        "description", stringParam("Подробности, если есть"),
                        "priority", enumParam("Приоритет", List.of("LOW", "MEDIUM", "HIGH", "URGENT")),
                        "deadline", stringParam("Срок в формате ISO-8601 со смещением, например 2026-08-12T18:00:00+03:00"),
                        "group", stringParam("Название группы одним-двумя словами на русском"),
                        "tags", arrayParam("Метки"),
                        "recurrence", enumParam("Повторяемость. NONE — задача разовая", List.of("NONE", "DAILY", "WEEKLY", "MONTHLY", "WEEKDAYS"))
                ), List.of("title")),

                tool(COMPLETE_TASK, "Отметить существующую задачу выполненной", Map.of(
                        "task_ref", stringParam("Ярлык задачи из списка, например T3"),
                        "note", stringParam("Короткий комментарий, если пользователь его дал")
                ), List.of("task_ref")),

                tool(RESCHEDULE_TASK, "Перенести срок существующей задачи", Map.of(
                        "task_ref", stringParam("Ярлык задачи из списка, например T3"),
                        "new_deadline", stringParam("Новый срок в формате ISO-8601 со смещением")
                ), List.of("task_ref", "new_deadline")),

                tool(UPDATE_TASK, "Изменить поля существующей задачи", Map.of(
                        "task_ref", stringParam("Ярлык задачи из списка, например T3"),
                        "title", stringParam("Новое название"),
                        "description", stringParam("Новое описание"),
                        "priority", enumParam("Новый приоритет", List.of("LOW", "MEDIUM", "HIGH", "URGENT")),
                        "group", stringParam("Новая группа")
                ), List.of("task_ref")),

                tool(CANCEL_TASK, "Отменить задачу, которая больше не актуальна", Map.of(
                        "task_ref", stringParam("Ярлык задачи из списка, например T3"),
                        "reason", stringParam("Причина отмены, если пользователь её назвал")
                ), List.of("task_ref")),

                tool(SEARCH_TASKS, "Найти задачи пользователя, если нужной нет в показанном списке", Map.of(
                        "query", stringParam("Поисковая фраза"),
                        "include_completed", Map.of("type", "boolean", "description", "Искать среди выполненных тоже")
                ), List.of("query")),

                tool(ASK_USER, "Задать пользователю один уточняющий вопрос. Использовать только когда ошибка привела бы к изменению не той задачи", Map.of(
                        "question", stringParam("Вопрос одним предложением"),
                        "options", arrayParam("Варианты ответа, от двух до четырёх")
                ), List.of("question", "options"))
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
