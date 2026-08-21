package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.task.api.TaskService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolCallParserTest {

    private final UUID taskId = UUID.randomUUID();
    private final TaskContextWindow window = new TaskContextWindow(
            "T1 · купить молоко", Map.of("T1", taskId), Map.of("T1", "купить молоко"));

    private final ToolCallParser parser = new ToolCallParser(
            new ToolRegistry(), new ActionValidator(mock(TaskService.class)), new SummaryRenderer(), new ObjectMapper());

    private ToolCall call(String name, String args) {
        return new ToolCall("id-1", name, args);
    }

    private ToolCall createTasksCall(String... titles) {
        String items = String.join(",", java.util.Arrays.stream(titles)
                .map(t -> "{\"title\":\"" + t + "\"}")
                .toArray(String[]::new));
        return call("create_tasks", "{\"tasks\":[" + items + "]}");
    }

    @Test
    void parse_buildsActionFromCompleteTask() {
        var result = parser.parse(List.of(call("complete_task", "{\"task_ref\":\"T1\"}")), window);

        assertThat(result.actions()).hasSize(1);
        var action = result.actions().getFirst();
        assertThat(action.type()).isEqualTo(AssistantActionType.COMPLETE);
        assertThat(action.targetTaskId()).isEqualTo(taskId);
        assertThat(action.summary()).isEqualTo("Закрыть — купить молоко");
        assertThat(action.ordinal()).isEqualTo(1);
        assertThat(action.accepted()).isTrue();
    }

    @Test
    void parse_buildsSingleActionFromSingleElementBatch() {
        var result = parser.parse(List.of(createTasksCall("хлеб")), window);

        assertThat(result.actions()).hasSize(1);
        var action = result.actions().getFirst();
        assertThat(action.type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(action.payload()).containsEntry("title", "хлеб");
        assertThat(action.ordinal()).isEqualTo(1);
        assertThat(action.accepted()).isTrue();
    }

    // Живой дефект: реплика с несколькими задачами устойчиво давала одно
    // действие — модель отвечает одним вызовом инструмента вне зависимости
    // от параллельных вызовов и явных инструкций. create_tasks разворачивает
    // один вызов с несколькими элементами в столько же отдельных действий.
    @Test
    void parse_buildsSeparateActionForEachTaskInBatch() {
        var result = parser.parse(List.of(createTasksCall("хлеб", "молоко")), window);

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions()).extracting("ordinal").containsExactly(1, 2);
        assertThat(result.actions().get(0).payload()).containsEntry("title", "хлеб");
        assertThat(result.actions().get(1).payload()).containsEntry("title", "молоко");
        assertThat(result.actions()).allSatisfy(a -> assertThat(a.type()).isEqualTo(AssistantActionType.CREATE));
        assertThat(result.actions()).allSatisfy(a -> assertThat(a.accepted()).isTrue());
    }

    @Test
    void parse_eachBatchElementGetsItsOwnSummary() {
        var result = parser.parse(List.of(createTasksCall("хлеб", "молоко")), window);

        assertThat(result.actions().get(0).summary()).isEqualTo("Создать — хлеб");
        assertThat(result.actions().get(1).summary()).isEqualTo("Создать — молоко");
    }

    // Отказ на одном элементе не роняет остальные: третий элемент без title
    // отклоняется, первые два остаются предложенными.
    @Test
    void parse_rejectsInvalidBatchElementButKeepsOthers() {
        var result = parser.parse(List.of(call("create_tasks",
                "{\"tasks\":[{\"title\":\"хлеб\"},{\"priority\":\"URGENT\"},{\"title\":\"молоко\"}]}")), window);

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0).payload()).containsEntry("title", "хлеб");
        assertThat(result.actions().get(1).payload()).containsEntry("title", "молоко");
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().getFirst()).contains("title");
    }

    @Test
    void parse_rejectsEmptyTasksList() {
        var result = parser.parse(List.of(call("create_tasks", "{\"tasks\":[]}")), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_normalisesNullStringToAbsentValueWithinBatchElement() {
        var result = parser.parse(List.of(
                call("create_tasks", "{\"tasks\":[{\"title\":\"хлеб\",\"deadline\":\"null\"}]}")), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().payload()).doesNotContainKey("deadline");
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void parse_normalisesEmptyStringToAbsentValueWithinBatchElement() {
        var result = parser.parse(List.of(
                call("create_tasks", "{\"tasks\":[{\"title\":\"хлеб\",\"description\":\"\"}]}")), window);

        assertThat(result.actions().getFirst().payload()).doesNotContainKey("description");
    }

    @Test
    void parse_rejectsInvalidActionButKeepsOthers() {
        var result = parser.parse(List.of(
                call("complete_task", "{\"task_ref\":\"T99\"}"),
                call("complete_task", "{\"task_ref\":\"T1\"}")), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().getFirst()).contains("T99");
    }

    @Test
    void parse_numbersActionsFromOne() {
        var result = parser.parse(List.of(
                call("complete_task", "{\"task_ref\":\"T1\"}"),
                createTasksCall("хлеб")), window);

        assertThat(result.actions()).extracting("ordinal").containsExactly(1, 2);
    }

    @Test
    void parse_capsActionsAtTwentyAcrossOneBatch() {
        var titles = new String[25];
        java.util.Arrays.fill(titles, "задача");

        var result = parser.parse(List.of(createTasksCall(titles)), window);

        assertThat(result.actions()).hasSize(20);
        assertThat(result.rejections()).isNotEmpty();
    }

    @Test
    void parse_capsActionsAtTwentyAcrossSeparateCalls() {
        var calls = new java.util.ArrayList<ToolCall>();
        for (int i = 0; i < 25; i++) {
            calls.add(createTasksCall("задача" + i));
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
                call("create_tasks", "{это не json"),
                createTasksCall("хлеб")), window);

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

    @Test
    void parse_extractsAmbiguousMarkWithReason() {
        var result = parser.parse(List.of(
                call("complete_task", "{\"task_ref\":\"T1\"}"),
                createTasksCall("купить молоко"),
                call("mark_ambiguous", "{\"reason\":\"не понял, про какое молоко речь\"}")), window);

        assertThat(result.ambiguous()).isTrue();
        assertThat(result.ambiguityReason()).isEqualTo("не понял, про какое молоко речь");
        assertThat(result.actions()).hasSize(2);
    }

    @Test
    void parse_marksOnlyFirstActionAcceptedWhenAmbiguous() {
        var result = parser.parse(List.of(
                call("complete_task", "{\"task_ref\":\"T1\"}"),
                createTasksCall("купить молоко"),
                call("mark_ambiguous", "{\"reason\":\"двоякая реплика\"}")), window);

        assertThat(result.actions()).extracting("accepted").containsExactly(true, false);
    }

    @Test
    void parse_doesNotTouchAcceptedWhenNotAmbiguous() {
        var result = parser.parse(List.of(
                call("complete_task", "{\"task_ref\":\"T1\"}"),
                createTasksCall("купить молоко")), window);

        assertThat(result.actions()).extracting("accepted").containsExactly(true, true);
    }

    @Test
    void parse_keepsOnlyFirstAmbiguousMark() {
        var result = parser.parse(List.of(
                call("mark_ambiguous", "{\"reason\":\"первая\"}"),
                call("mark_ambiguous", "{\"reason\":\"вторая\"}")), window);

        assertThat(result.ambiguityReason()).isEqualTo("первая");
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void parse_capturesRejectedTargetFromResolvedRefValidationFailure() {
        var result = parser.parse(List.of(call("update_task", "{\"task_ref\":\"T1\"}")), window);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejectedTarget()).isEqualTo(taskId);
    }

    @Test
    void parse_rejectedTargetNullWhenRefUnknown() {
        var result = parser.parse(List.of(call("update_task", "{\"task_ref\":\"T99\"}")), window);

        assertThat(result.rejectedTarget()).isNull();
    }

    @Test
    void parse_rejectedTargetNullForUnrelatedRejection() {
        var result = parser.parse(List.of(call("delete_everything", "{}")), window);

        assertThat(result.rejectedTarget()).isNull();
    }

    @Test
    void parse_ambiguousDefaultsFalse() {
        var result = parser.parse(List.of(call("complete_task", "{\"task_ref\":\"T1\"}")), window);

        assertThat(result.ambiguous()).isFalse();
        assertThat(result.ambiguityReason()).isNull();
    }
}
