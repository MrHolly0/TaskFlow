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
                ToolRegistry.CREATE_TASK,
                ToolRegistry.COMPLETE_TASK,
                ToolRegistry.RESCHEDULE_TASK,
                ToolRegistry.UPDATE_TASK,
                ToolRegistry.CANCEL_TASK,
                ToolRegistry.SEARCH_TASKS,
                ToolRegistry.ASK_USER
        );
    }

    @Test
    void actionTypeOf_mapsActionTools() {
        assertThat(registry.actionTypeOf(ToolRegistry.CREATE_TASK)).isEqualTo(AssistantActionType.CREATE);
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

    @Test
    void isRetrieval_trueOnlyForSearch() {
        assertThat(registry.isRetrieval(ToolRegistry.SEARCH_TASKS)).isTrue();
        assertThat(registry.isRetrieval(ToolRegistry.CREATE_TASK)).isFalse();
    }

    @Test
    void isControl_trueOnlyForAskUser() {
        assertThat(registry.isControl(ToolRegistry.ASK_USER)).isTrue();
        assertThat(registry.isControl(ToolRegistry.SEARCH_TASKS)).isFalse();
    }
}
