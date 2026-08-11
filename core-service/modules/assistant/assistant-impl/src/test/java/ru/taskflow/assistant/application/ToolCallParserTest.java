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
    void parse_normalisesNullStringToAbsentValue() {
        var result = parser.parse(List.of(
                call("create_task", "{\"title\":\"хлеб\",\"deadline\":\"null\"}")), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().getFirst().payload()).doesNotContainKey("deadline");
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void parse_normalisesEmptyStringToAbsentValue() {
        var result = parser.parse(List.of(
                call("create_task", "{\"title\":\"хлеб\",\"description\":\"\"}")), window);

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
                call("create_task", "{\"title\":\"хлеб\"}")), window);

        assertThat(result.actions()).extracting("ordinal").containsExactly(1, 2);
    }

    @Test
    void parse_capsActionsAtTwenty() {
        var calls = new java.util.ArrayList<ToolCall>();
        for (int i = 0; i < 25; i++) {
            calls.add(call("create_task", "{\"title\":\"задача\"}"));
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
                call("create_task", "{это не json"),
                call("create_task", "{\"title\":\"хлеб\"}")), window);

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
}
