package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    private final ToolRegistry registry = new ToolRegistry();

    @Test
    void toolDefinitions_containsThreeTools() {
        List<Map<String, Object>> definitions = registry.toolDefinitions();

        assertThat(definitions).hasSize(3);
        assertThat(definitions).allSatisfy(d -> assertThat(d).containsKey("function"));
    }

    @Test
    void toolDefinitions_namesMatchConstants() {
        List<String> names = registry.toolDefinitions().stream()
                .map(d -> (Map<String, Object>) d.get("function"))
                .map(f -> (String) f.get("name"))
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                ToolRegistry.PROPOSE_ACTIONS,
                ToolRegistry.SEARCH_TASKS,
                ToolRegistry.NO_ACTION
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
    void isProposeActions_trueOnlyForProposeActions() {
        assertThat(registry.isProposeActions(ToolRegistry.PROPOSE_ACTIONS)).isTrue();
        assertThat(registry.isProposeActions(ToolRegistry.SEARCH_TASKS)).isFalse();
    }

    @Test
    void isRetrieval_trueOnlyForSearch() {
        assertThat(registry.isRetrieval(ToolRegistry.SEARCH_TASKS)).isTrue();
        assertThat(registry.isRetrieval(ToolRegistry.PROPOSE_ACTIONS)).isFalse();
    }

    @Test
    void isControl_trueOnlyForAskUser() {
        assertThat(registry.isControl(ToolRegistry.ASK_USER)).isTrue();
        assertThat(registry.isControl(ToolRegistry.SEARCH_TASKS)).isFalse();
        assertThat(registry.isControl(ToolRegistry.PROPOSE_ACTIONS)).isFalse();
    }

    @Test
    void isNoAction_trueOnlyForNoAction() {
        assertThat(registry.isNoAction(ToolRegistry.NO_ACTION)).isTrue();
        assertThat(registry.isNoAction(ToolRegistry.PROPOSE_ACTIONS)).isFalse();
        assertThat(registry.isNoAction(ToolRegistry.SEARCH_TASKS)).isFalse();
    }

    @Test
    void toolDefinitions_noActionListsClosedReasonSet() {
        var noAction = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.NO_ACTION.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> properties = parameters(noAction);
        assertThat(properties).containsOnlyKeys("reason", "answer");

        Map<String, Object> reasonParam = (Map<String, Object>) properties.get("reason");
        assertThat((List<String>) reasonParam.get("enum"))
                .containsExactlyInAnyOrder("question", "chitchat", "unclear");
    }

    @Test
    void toolDefinitions_noActionRequiresReasonAndAnswer() {
        var noAction = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.NO_ACTION.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> function = (Map<String, Object>) noAction.get("function");
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");

        assertThat((List<String>) parameters.get("required")).containsExactlyInAnyOrder("reason", "answer");
    }

    @Test
    void toolDefinitions_proposeActionsTakesAnArrayOfActionObjects() {
        var proposeActions = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.PROPOSE_ACTIONS.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> properties = parameters(proposeActions);
        assertThat(properties).containsOnlyKeys("actions");

        Map<String, Object> actionsParam = (Map<String, Object>) properties.get("actions");
        assertThat(actionsParam.get("type")).isEqualTo("array");
        assertThat(itemPropertyNames(actionsParam)).contains(
                "type", "task_ref", "title", "description", "priority", "deadline",
                "new_deadline", "group", "tags", "note", "reason", "ambiguous_reason");
    }

    @Test
    void toolDefinitions_actionTypeFieldListsAllFiveKinds() {
        var proposeActions = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.PROPOSE_ACTIONS.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> actionsParam = (Map<String, Object>) parameters(proposeActions).get("actions");
        Map<String, Object> items = (Map<String, Object>) actionsParam.get("items");
        Map<String, Object> itemProperties = (Map<String, Object>) items.get("properties");
        Map<String, Object> typeProperty = (Map<String, Object>) itemProperties.get("type");

        assertThat((List<String>) typeProperty.get("enum"))
                .containsExactlyInAnyOrder("create", "complete", "reschedule", "update", "cancel");
    }

    // Живой дефект: модель устойчиво возвращала один вызов инструмента на
    // ответ вне зависимости от parallel_tool_calls и прямых инструкций —
    // описание инструмента должно прямо говорить, что вызов один, а действия
    // перечисляются внутри него, а не полагаться на догадку модели.
    @Test
    void toolDefinitions_proposeActionsDescriptionSaysCalledOnce() {
        var proposeActions = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.PROPOSE_ACTIONS.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> function = (Map<String, Object>) proposeActions.get("function");
        String description = (String) function.get("description");

        assertThat(description).contains("один раз");
        assertThat(description).contains("списка");
    }

    @Test
    void toolDefinitions_doNotPromiseRecurrence() {
        var proposeActions = registry.toolDefinitions().stream()
                .filter(t -> ToolRegistry.PROPOSE_ACTIONS.equals(functionName(t)))
                .findFirst().orElseThrow();

        Map<String, Object> actionsParam = (Map<String, Object>) parameters(proposeActions).get("actions");
        assertThat(itemPropertyNames(actionsParam)).doesNotContain("recurrence");
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
