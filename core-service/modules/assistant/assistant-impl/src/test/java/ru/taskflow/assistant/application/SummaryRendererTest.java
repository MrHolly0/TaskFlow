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
    void render_createIncludesDeadlineWhenPresent() {
        // Подтверждение перед применением защищает ровно настолько, насколько
        // сводка показывает то, что подтверждают — без этого неверный срок
        // (например 01:02 вместо через час — Task 0) было негде увидеть.
        var result = renderer.render(AssistantActionType.CREATE, null,
                Map.of("title", "сдать отчёт", "deadline", "2026-08-14T18:00:00+03:00"));

        assertThat(result).isEqualTo("Создать — сдать отчёт · до 14.08 18:00");
    }

    @Test
    void render_createOmitsDeadlineMentionWhenAbsent() {
        var result = renderer.render(AssistantActionType.CREATE, null, Map.of("title", "сдать отчёт"));

        assertThat(result).isEqualTo("Создать — сдать отчёт");
        assertThat(result).doesNotContain("срок");
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
    void render_updateListsAllFieldsUpdateTaskCanChange() {
        // Список полей здесь и в ActionValidator.UPDATABLE_FIELDS должны совпадать
        // с параметрами update_task в ToolRegistry — иначе новое поле у инструмента
        // молча не попадёт в сводку, и подтверждение перестанет что-то подтверждать.
        var result = renderer.render(AssistantActionType.UPDATE, "сдать отчёт", Map.of(
                "title", "новое название",
                "description", "новое описание",
                "priority", "HIGH",
                "group", "Работа"
        ));

        assertThat(result).contains("название");
        assertThat(result).contains("описание");
        assertThat(result).contains("приоритет");
        assertThat(result).contains("группа");
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

    // --- Блок В: remind ---

    @Test
    void render_remindUsesExistingTaskTitleAndTime() {
        var result = renderer.render(AssistantActionType.REMIND, "позвонить маме",
                Map.of("reminder_at", "2026-08-13T09:00:00+03:00"));

        assertThat(result).isEqualTo("Напомнить — позвонить маме → 13.08 09:00");
    }

    @Test
    void render_createIncludesReminderWhenPresent() {
        var result = renderer.render(AssistantActionType.CREATE, null,
                Map.of("title", "позвонить маме", "reminder_at", "2026-08-13T09:00:00+03:00"));

        assertThat(result).isEqualTo("Создать — позвонить маме · напомнить 13.08 09:00");
    }

    @Test
    void render_createOmitsReminderMentionWhenAbsent() {
        var result = renderer.render(AssistantActionType.CREATE, null, Map.of("title", "сдать отчёт"));

        assertThat(result).doesNotContain("напомнить");
    }
}
