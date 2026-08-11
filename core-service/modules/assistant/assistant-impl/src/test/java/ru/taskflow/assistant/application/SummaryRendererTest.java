package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryRendererTest {

    private final SummaryRenderer renderer = new SummaryRenderer();

    @Test
    void render_createUsesTitleFromPayload() {
        var result = renderer.render(AssistantActionType.CREATE, null, Map.of("title", "созвониться с Марком"));

        assertThat(result).isEqualTo("Создать — созвониться с Марком");
    }

    @Test
    void render_completeUsesExistingTaskTitle() {
        var result = renderer.render(AssistantActionType.COMPLETE, "купить молоко", Map.of());

        assertThat(result).isEqualTo("Закрыть — купить молоко");
    }

    @Test
    void render_rescheduleIncludesNewDeadline() {
        var result = renderer.render(AssistantActionType.RESCHEDULE, "сдать отчёт",
                Map.of("new_deadline", "2026-08-14T18:00:00+03:00"));

        assertThat(result).startsWith("Перенести — сдать отчёт");
        assertThat(result).contains("14.08");
    }

    @Test
    void render_cancelUsesExistingTaskTitle() {
        var result = renderer.render(AssistantActionType.CANCEL, "встреча с Марком", Map.of());

        assertThat(result).isEqualTo("Отменить — встреча с Марком");
    }

    @Test
    void render_updateListsChangedFields() {
        var result = renderer.render(AssistantActionType.UPDATE, "сдать отчёт",
                Map.of("priority", "HIGH"));

        assertThat(result).startsWith("Изменить — сдать отчёт");
        assertThat(result).contains("приоритет");
    }

    @Test
    void render_truncatesLongTitle() {
        String longTitle = "я".repeat(400);

        var result = renderer.render(AssistantActionType.CREATE, null, Map.of("title", longTitle));

        assertThat(result).hasSizeLessThanOrEqualTo(256);
        assertThat(result).endsWith("…");
    }

    @Test
    void render_handlesMissingTitleGracefully() {
        var result = renderer.render(AssistantActionType.CREATE, null, Map.of());

        assertThat(result).isEqualTo("Создать — без названия");
    }
}
