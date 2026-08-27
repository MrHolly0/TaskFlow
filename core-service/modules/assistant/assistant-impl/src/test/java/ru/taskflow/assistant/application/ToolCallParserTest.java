package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.DeclineReason;
import ru.taskflow.task.api.TaskService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolCallParserTest {

    private final UUID taskId = UUID.randomUUID();
    private final TaskContextWindow window = new TaskContextWindow(
            "T1 · купить молоко", Map.of("T1", taskId), Map.of("T1", "купить молоко"));
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T10:00:00Z"), ZoneOffset.UTC);

    private final ToolCallParser parser = new ToolCallParser(
            new ToolRegistry(), new ActionValidator(mock(TaskService.class), clock), new SummaryRenderer(), new ObjectMapper());

    private ToolCall call(String name, String args) {
        return new ToolCall("id-1", name, args);
    }

    private ToolCall proposeActions(String... items) {
        return call("propose_actions", "{\"actions\":[" + String.join(",", items) + "]}");
    }

    private String completeItem(String taskRef) {
        return "{\"type\":\"complete\",\"task_ref\":\"" + taskRef + "\"}";
    }

    private String createItem(String title) {
        return "{\"type\":\"create\",\"title\":\"" + title + "\"}";
    }

    private String completeItemAmbiguous(String taskRef, String reason) {
        return "{\"type\":\"complete\",\"task_ref\":\"" + taskRef + "\",\"ambiguous_reason\":\"" + reason + "\"}";
    }

    private String createItemAmbiguous(String title, String reason) {
        return "{\"type\":\"create\",\"title\":\"" + title + "\",\"ambiguous_reason\":\"" + reason + "\"}";
    }

    @Test
    void parse_buildsActionFromCompleteItem() {
        var result = parser.parse(List.of(proposeActions(completeItem("T1"))), window);

        assertThat(result.actions()).hasSize(1);
        var action = result.actions().getFirst();
        assertThat(action.type()).isEqualTo(AssistantActionType.COMPLETE);
        assertThat(action.targetTaskId()).isEqualTo(taskId);
        assertThat(action.summary()).isEqualTo("Закрыть — купить молоко");
        assertThat(action.ordinal()).isEqualTo(1);
        assertThat(action.accepted()).isTrue();
    }

    @Test
    void parse_actionTypeIsCaseInsensitive() {
        var result = parser.parse(List.of(call("propose_actions",
                "{\"actions\":[{\"type\":\"COMPLETE\",\"task_ref\":\"T1\"}]}")), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().type()).isEqualTo(AssistantActionType.COMPLETE);
    }

    @Test
    void parse_rejectsUnknownActionType() {
        var result = parser.parse(List.of(proposeActions("{\"type\":\"delete\",\"task_ref\":\"T1\"}")), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().getFirst()).contains("delete");
    }

    @Test
    void parse_buildsSingleActionFromSingleElementBatch() {
        var result = parser.parse(List.of(proposeActions(createItem("хлеб"))), window);

        assertThat(result.actions()).hasSize(1);
        var action = result.actions().getFirst();
        assertThat(action.type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(action.payload()).containsEntry("title", "хлеб");
        assertThat(action.ordinal()).isEqualTo(1);
        assertThat(action.accepted()).isTrue();
    }

    // Живой дефект: реплика с несколькими задачами устойчиво давала одно
    // действие — модель отвечает одним вызовом инструмента вне зависимости
    // от параллельных вызовов и явных инструкций. propose_actions разворачивает
    // один вызов с несколькими элементами в столько же отдельных действий.
    @Test
    void parse_buildsSeparateActionForEachTaskInBatch() {
        var result = parser.parse(List.of(proposeActions(createItem("хлеб"), createItem("молоко"))), window);

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions()).extracting("ordinal").containsExactly(1, 2);
        assertThat(result.actions().get(0).payload()).containsEntry("title", "хлеб");
        assertThat(result.actions().get(1).payload()).containsEntry("title", "молоко");
        assertThat(result.actions()).allSatisfy(a -> assertThat(a.type()).isEqualTo(AssistantActionType.CREATE));
        assertThat(result.actions()).allSatisfy(a -> assertThat(a.accepted()).isTrue());
    }

    // Смешанный пакет — разные виды действий одним вызовом, ровно то, что
    // отдельные инструменты по типу действия делать не могли (одна модель —
    // один вызов инструмента за ответ).
    @Test
    void parse_buildsMixedActionsFromOneCall() {
        var result = parser.parse(List.of(proposeActions(completeItem("T1"), createItem("молоко"))), window);

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0).type()).isEqualTo(AssistantActionType.COMPLETE);
        assertThat(result.actions().get(1).type()).isEqualTo(AssistantActionType.CREATE);
    }

    @Test
    void parse_eachBatchElementGetsItsOwnSummary() {
        var result = parser.parse(List.of(proposeActions(createItem("хлеб"), createItem("молоко"))), window);

        assertThat(result.actions().get(0).summary()).isEqualTo("Создать — хлеб");
        assertThat(result.actions().get(1).summary()).isEqualTo("Создать — молоко");
    }

    // Отказ на одном элементе не роняет остальные: второй элемент без title
    // отклоняется, первые и третий остаются предложенными.
    @Test
    void parse_rejectsInvalidBatchElementButKeepsOthers() {
        var result = parser.parse(List.of(proposeActions(
                createItem("хлеб"),
                "{\"type\":\"create\",\"priority\":\"URGENT\"}",
                createItem("молоко"))), window);

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0).payload()).containsEntry("title", "хлеб");
        assertThat(result.actions().get(1).payload()).containsEntry("title", "молоко");
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().getFirst()).contains("title");
    }

    @Test
    void parse_rejectsEmptyActionsList() {
        var result = parser.parse(List.of(call("propose_actions", "{\"actions\":[]}")), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_normalisesNullStringToAbsentValueWithinBatchElement() {
        var result = parser.parse(List.of(proposeActions(
                "{\"type\":\"create\",\"title\":\"хлеб\",\"deadline\":\"null\"}")), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().payload()).doesNotContainKey("deadline");
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void parse_normalisesEmptyStringToAbsentValueWithinBatchElement() {
        var result = parser.parse(List.of(proposeActions(
                "{\"type\":\"create\",\"title\":\"хлеб\",\"description\":\"\"}")), window);

        assertThat(result.actions().getFirst().payload()).doesNotContainKey("description");
    }

    @Test
    void parse_rejectsInvalidActionButKeepsOthers() {
        var result = parser.parse(List.of(proposeActions(
                completeItem("T99"), completeItem("T1"))), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().getFirst()).contains("T99");
    }

    @Test
    void parse_numbersActionsFromOne() {
        var result = parser.parse(List.of(proposeActions(completeItem("T1"), createItem("хлеб"))), window);

        assertThat(result.actions()).extracting("ordinal").containsExactly(1, 2);
    }

    @Test
    void parse_capsActionsAtTwentyAcrossOneBatch() {
        var items = new String[25];
        java.util.Arrays.fill(items, createItem("задача"));

        var result = parser.parse(List.of(proposeActions(items)), window);

        assertThat(result.actions()).hasSize(20);
        assertThat(result.rejections()).isNotEmpty();
    }

    @Test
    void parse_capsActionsAtTwentyAcrossSeparateCalls() {
        var calls = new java.util.ArrayList<ToolCall>();
        for (int i = 0; i < 25; i++) {
            calls.add(proposeActions(createItem("задача" + i)));
        }

        var result = parser.parse(calls, window);

        assertThat(result.actions()).hasSize(20);
        assertThat(result.rejections()).isNotEmpty();
    }

    @Test
    void parse_extractsSearchQueryWithoutCreatingAction() {
        var result = parser.parse(List.of(call("search_tasks", "{\"query\":\"аптека\"}")), window);

        assertThat(result.searchQuery()).isEqualTo("аптека");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void parse_keepsOnlyFirstSearchQuery() {
        var result = parser.parse(List.of(
                call("search_tasks", "{\"query\":\"первый\"}"),
                call("search_tasks", "{\"query\":\"второй\"}")), window);

        assertThat(result.searchQuery()).isEqualTo("первый");
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_extractsClarification() {
        var result = parser.parse(List.of(call("ask_user",
                "{\"question\":\"какой Марк?\",\"options\":[\"Петров\",\"Новый\"]}")), window);

        assertThat(result.clarification()).isEqualTo("какой Марк?");
        assertThat(result.clarificationOptions()).containsExactly("Петров", "Новый");
    }

    @Test
    void parse_keepsOnlyFirstClarification() {
        var result = parser.parse(List.of(
                call("ask_user", "{\"question\":\"первый\",\"options\":[\"а\",\"б\"]}"),
                call("ask_user", "{\"question\":\"второй\",\"options\":[\"в\",\"г\"]}")), window);

        assertThat(result.clarification()).isEqualTo("первый");
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_toleratesBrokenArgumentsJson() {
        var result = parser.parse(List.of(
                call("propose_actions", "{это не json"),
                proposeActions(createItem("хлеб"))), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_ignoresUnknownTool() {
        var result = parser.parse(List.of(call("delete_everything", "{}")), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_returnsEmptyResultForNoCalls() {
        var result = parser.parse(List.of(), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.clarification()).isNull();
        assertThat(result.searchQuery()).isNull();
    }

    // Признак двоякости — поле того же элемента списка actions, а не отдельный
    // инструмент: модель делает ровно один вызов инструмента за ответ, так что
    // отдельный «пометь неоднозначность» вызов вместе с действиями сработать
    // не мог (та же причина, по которой не работал пакет создания).
    @Test
    void parse_extractsAmbiguousReasonFromActionItem() {
        var result = parser.parse(List.of(proposeActions(
                completeItemAmbiguous("T1", "не понял, про какое молоко речь"),
                createItemAmbiguous("купить молоко", "не понял, про какое молоко речь"))), window);

        assertThat(result.ambiguous()).isTrue();
        assertThat(result.ambiguityReason()).isEqualTo("не понял, про какое молоко речь");
        assertThat(result.actions()).hasSize(2);
    }

    @Test
    void parse_marksOnlyFirstActionAcceptedWhenAmbiguous() {
        var result = parser.parse(List.of(proposeActions(
                completeItemAmbiguous("T1", "двоякая реплика"),
                createItem("купить молоко"))), window);

        assertThat(result.actions()).extracting("accepted").containsExactly(true, false);
    }

    @Test
    void parse_doesNotTouchAcceptedWhenNotAmbiguous() {
        var result = parser.parse(List.of(proposeActions(completeItem("T1"), createItem("купить молоко"))), window);

        assertThat(result.actions()).extracting("accepted").containsExactly(true, true);
    }

    @Test
    void parse_keepsFirstAmbiguousReasonWhenMultipleItemsCarryOne() {
        var result = parser.parse(List.of(proposeActions(
                completeItemAmbiguous("T1", "первая"),
                createItemAmbiguous("что-то", "вторая"))), window);

        assertThat(result.ambiguityReason()).isEqualTo("первая");
    }

    @Test
    void parse_capturesRejectedTargetFromResolvedRefValidationFailure() {
        var result = parser.parse(List.of(proposeActions("{\"type\":\"update\",\"task_ref\":\"T1\"}")), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejectedTarget()).isEqualTo(taskId);
    }

    @Test
    void parse_rejectedTargetNullWhenRefUnknown() {
        var result = parser.parse(List.of(proposeActions("{\"type\":\"update\",\"task_ref\":\"T99\"}")), window);

        assertThat(result.rejectedTarget()).isNull();
    }

    @Test
    void parse_rejectedTargetNullForUnrelatedRejection() {
        var result = parser.parse(List.of(call("delete_everything", "{}")), window);

        assertThat(result.rejectedTarget()).isNull();
    }

    @Test
    void parse_ambiguousDefaultsFalse() {
        var result = parser.parse(List.of(proposeActions(completeItem("T1"))), window);

        assertThat(result.ambiguous()).isFalse();
        assertThat(result.ambiguityReason()).isNull();
    }

    // --- Блок В: remind ---

    @Test
    void parse_buildsActionFromRemindItem() {
        var result = parser.parse(List.of(proposeActions(
                "{\"type\":\"remind\",\"task_ref\":\"T1\",\"reminder_at\":\"2026-08-13T09:00:00Z\"}")), window);

        assertThat(result.actions()).hasSize(1);
        var action = result.actions().getFirst();
        assertThat(action.type()).isEqualTo(AssistantActionType.REMIND);
        assertThat(action.targetTaskId()).isEqualTo(taskId);
        assertThat(action.payload()).containsEntry("reminder_at", "2026-08-13T09:00:00Z");
    }

    @Test
    void parse_rejectsRemindInThePast() {
        var result = parser.parse(List.of(proposeActions(
                "{\"type\":\"remind\",\"task_ref\":\"T1\",\"reminder_at\":\"2026-08-01T09:00:00Z\"}")), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().getFirst()).contains("прошло");
    }

    // --- Блок Б: no_action ---

    @Test
    void parse_extractsNoAction() {
        var result = parser.parse(List.of(call("no_action",
                "{\"reason\":\"question\",\"answer\":\"На завтра задач нет.\"}")), window);

        assertThat(result.isDeclined()).isTrue();
        assertThat(result.declineReason()).isEqualTo(DeclineReason.QUESTION);
        assertThat(result.declineAnswer()).isEqualTo("На завтра задач нет.");
        assertThat(result.actions()).isEmpty();
    }

    @Test
    void parse_noActionReasonIsCaseInsensitive() {
        var result = parser.parse(List.of(call("no_action",
                "{\"reason\":\"CHITCHAT\",\"answer\":\"Пожалуйста!\"}")), window);

        assertThat(result.declineReason()).isEqualTo(DeclineReason.CHITCHAT);
    }

    // Пункт 2: напоминание на прошедший момент — отдельная, четвёртая причина.
    @Test
    void parse_extractsPastReason() {
        var result = parser.parse(List.of(call("no_action",
                "{\"reason\":\"past\",\"answer\":\"Это время уже прошло.\"}")), window);

        assertThat(result.declineReason()).isEqualTo(DeclineReason.PAST);
        assertThat(result.declineAnswer()).isEqualTo("Это время уже прошло.");
    }

    @Test
    void parse_rejectsNoActionWithUnknownReason() {
        var result = parser.parse(List.of(call("no_action",
                "{\"reason\":\"maybe\",\"answer\":\"что-то\"}")), window);

        assertThat(result.isDeclined()).isFalse();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().getFirst()).contains("maybe");
    }

    @Test
    void parse_keepsOnlyFirstNoAction() {
        var result = parser.parse(List.of(
                call("no_action", "{\"reason\":\"question\",\"answer\":\"первый\"}"),
                call("no_action", "{\"reason\":\"chitchat\",\"answer\":\"второй\"}")), window);

        assertThat(result.declineAnswer()).isEqualTo("первый");
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_noActionDefaultsAbsent() {
        var result = parser.parse(List.of(proposeActions(completeItem("T1"))), window);

        assertThat(result.isDeclined()).isFalse();
        assertThat(result.declineReason()).isNull();
        assertThat(result.declineAnswer()).isNull();
    }
}
