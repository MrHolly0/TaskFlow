package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private final ToolRegistry registry = new ToolRegistry();

    @Test
    void toolDefinitions_containsAllSevenTools() {
        List<Map<String, Object>> definitions = registry.toolDefinitions();

        assertThat(definitions).hasSize(7);
        assertThat(definitions).allSatisfy(d -> assertThat(d).containsKey("function"));
    }

    @Test
    void toolDefinitions_namesMatchConstants() {
        List<String> names = registry.toolDefinitions().stream()
                .map(d -> (Map<String, Object>) d.get("function"))
                .map(f -> (String) f.get("name"))
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                ToolRegistry.CREATE_TASKS,
                ToolRegistry.COMPLETE_TASK,
                ToolRegistry.RESCHEDULE_TASK,
                ToolRegistry.UPDATE_TASK,
                ToolRegistry.CANCEL_TASK,
                ToolRegistry.SEARCH_TASKS,
                ToolRegistry.MARK_AMBIGUOUS
        );
    }

    @Test
    void toolDefinitions_noLongerOffersAskUser() {
        List<String> names = registry.toolDefinitions().stream()
                .map(d -> (Map<String, Object>) d.get("function"))
                .map(f -> (String) f.get("name"))
                .toList();

        assertThat(names).doesNotContain(ToolRegistry.ASK_USER);
    }

    @Test
    void actionTypeOf_mapsActionTools() {
        assertThat(registry.actionTypeOf(ToolRegistry.COMPLETE_TASK)).isEqualTo(AssistantActionType.COMPLETE);
        assertThat(registry.actionTypeOf(ToolRegistry.RESCHEDULE_TASK)).isEqualTo(AssistantActionType.RESCHEDULE);
        assertThat(registry.actionTypeOf(ToolRegistry.UPDATE_TASK)).isEqualTo(AssistantActionType.UPDATE);
        assertThat(registry.actionTypeOf(ToolRegistry.CANCEL_TASK)).isEqualTo(AssistantActionType.CANCEL);
    }

    @Test
    void actionTypeOf_returnsNullForNonActionTools() {
        assertThat(registry.actionTypeOf(ToolRegistry.SEARCH_TASKS)).isNull();
        assertThat(registry.actionTypeOf(ToolRegistry.ASK_USER)).isNull();
        assertThat(registry.actionTypeOf("несуществующий")).isNull();
    }

    // create_tasks разворачивается в несколько действий, а не в одно — он
    // сознательно не участвует в этой карте (см. isBatchCreate ниже).
    @Test
    void actionTypeOf_returnsNullForBatchCreate() {
        assertThat(registry.actionTypeOf(ToolRegistry.CREATE_TASKS)).isNull();
    }

    @Test
    void isBatchCreate_trueOnlyForCreateTasks() {
        assertThat(registry.isBatchCreate(ToolRegistry.CREATE_TASKS)).isTrue();
        assertThat(registry.isBatchCreate(ToolRegistry.COMPLETE_TASK)).isFalse();
        assertThat(registry.isBatchCreate(ToolRegistry.SEARCH_TASKS)).isFalse();
    }

    @Test
    void isRetrieval_trueOnlyForSearch() {
        assertThat(registry.isRetrieval(ToolRegistry.SEARCH_TASKS)).isTrue();
        assertThat(registry.isRetrieval(ToolRegistry.CREATE_TASKS)).isFalse();
    }

    @Test
    void isControl_trueOnlyForAskUser() {
        assertThat(registry.isControl(ToolRegistry.ASK_USER)).isTrue();
        assertThat(registry.isControl(ToolRegistry.SEARCH_TASKS)).isFalse();
        assertThat(registry.isControl(ToolRegistry.MARK_AMBIGUOUS)).isFalse();
    }

    @Test
    void isAmbiguityMarker_trueOnlyForMarkAmbiguous() {
        assertThat(registry.isAmbiguityMarker(ToolRegistry.MARK_AMBIGUOUS)).isTrue();
        assertThat(registry.isAmbiguityMarker(ToolRegistry.ASK_USER)).isFalse();
        assertThat(registry.isAmbiguityMarker(ToolRegistry.CREATE_TASKS)).isFalse();
    }

    @Test
    void toolDefinitions_createTasksTakesAnArrayOfTaskObjects() {
        var createTasks = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.CREATE_TASKS.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> properties = parameters(createTasks);
        assertThat(properties).containsOnlyKeys("tasks");

        Map<String, Object> tasksParam = (Map<String, Object>) properties.get("tasks");
        assertThat(tasksParam.get("type")).isEqualTo("array");
        assertThat(itemPropertyNames(tasksParam)).contains("title", "description", "priority", "deadline", "group", "tags");
    }

    // Живой дефект: модель устойчиво возвращала один вызов инструмента на
    // ответ вне зависимости от parallel_tool_calls и прямых инструкций —
    // описание инструмента должно прямо говорить, что вызов один, а задачи
    // перечисляются внутри него, а не полагаться на догадку модели.
    @Test
    void toolDefinitions_createTasksDescriptionSaysCalledOnce() {
        var createTasks = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.CREATE_TASKS.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> function = (Map<String, Object>) createTasks.get("function");
        String description = (String) function.get("description");

        assertThat(description).contains("один раз");
        assertThat(description).contains("списка");
    }

    @Test
    void toolDefinitions_doNotPromiseRecurrence() {
        var createTasks = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.CREATE_TASKS.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> tasksParam = (Map<String, Object>) parameters(createTasks).get("tasks");
        assertThat(itemPropertyNames(tasksParam)).doesNotContain("recurrence");
    }

    private String functionName(Map<String, Object> tool) {
        Map<String, Object> function = (Map<String, Object>) tool.get("function");
        return (String) function.get("name");
    }

    private Map<String, Object> parameters(Map<String, Object> tool) {
        Map<String, Object> function = (Map<String, Object>) tool.get("function");
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        return (Map<String, Object>) parameters.get("properties");
    }

    private List<String> itemPropertyNames(Map<String, Object> arrayParam) {
        Map<String, Object> items = (Map<String, Object>) arrayParam.get("items");
        Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
        return List.copyOf(itemProperties.keySet());
    }
}
