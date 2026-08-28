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
                                                        List.of("create", "complete", "reschedule", "update", "cancel", "remind"))),
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
                                                Map.entry("planned_date", stringParam(
                                                        "Дата в формате ISO-8601 — предположение «когда "
                                                                + "планирую заняться», не обязательство, в "
                                                                + "отличие от deadline. Для create и только "
                                                                + "когда у задачи нет прямо названного "
                                                                + "deadline: можно предложить день самой по "
                                                                + "смыслу задачи. Пропуск этого поля не "
                                                                + "создаёт просрочки — она считается только "
                                                                + "по deadline.")),
                                                Map.entry("no_planned_date_needed", Map.of(
                                                        "type", "boolean",
                                                        "description", "true — сознательно решил не "
                                                                + "предлагать день исполнения для create "
                                                                + "(уже есть deadline, или дело без всякой "
                                                                + "спешки). Пустые planned_date и "
                                                                + "no_planned_date_needed неотличимы от "
                                                                + "того, что ты забыл решить — выставляй "
                                                                + "одно из двух всегда."
                                                )),
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
                                                Map.entry("reminder_at", stringParam(
                                                        "Абсолютное время напоминания в формате ISO-8601 со "
                                                                + "смещением — для remind (обязательно) и опционально "
                                                                + "для create. Срок задачи и время напоминания — "
                                                                + "разные вещи, remind не меняет deadline. Заполняй и "
                                                                + "когда время названо в реплике прямо, и когда его "
                                                                + "можно осмысленно вывести из сути задачи — с отступом "
                                                                + "на дорогу, накануне для дела, требующего подготовки, "
                                                                + "в рабочие часы для звонков в организации, а не когда "
                                                                + "придётся. Если время назвал сам пользователь и оно "
                                                                + "уже прошло — не заполняй это поле и не создавай "
                                                                + "задачу вместо него: вызови no_action с reason=past. "
                                                                + "Если время лишь подобрано тобой и получилось "
                                                                + "прошедшим — no_action здесь неуместен, просто "
                                                                + "оставь поле пустым, остальное действие предлагай как "
                                                                + "обычно.")),
                                                Map.entry("no_reminder_needed", Map.of(
                                                        "type", "boolean",
                                                        "description", "true — сознательно решил не предлагать "
                                                                + "напоминание для create, дело не про конкретный "
                                                                + "момент («купить корм»). Пустые reminder_at и "
                                                                + "no_reminder_needed неотличимы от того, что ты "
                                                                + "забыл решить — выставляй одно из двух всегда."
                                                )),
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
                        + "над задачами: вопрос о данных, реплика без содержания или формулировка, из "
                        + "которой не восстанавливается ни одна команда. Короткая фраза, совпадающая по "
                        + "смыслу с активной задачей из списка, — не unclear: команда восстанавливается, "
                        + "просто неясно, о какой задаче речь, это двоякость через ambiguous_reason в "
                        + "propose_actions, а не отказ. Отдельная причина — past: реплика просит напомнить "
                        + "на время, которое уже прошло, — не создавай задачу вместо этого. Заменяет "
                        + "действие, а не дополняет его — одного вызова достаточно.", Map.of(
                        "reason", enumParam("Почему действие не предлагается",
                                List.of("question", "chitchat", "unclear", "past")),
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
