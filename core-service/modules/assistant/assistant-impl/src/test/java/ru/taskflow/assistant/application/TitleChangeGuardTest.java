package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TitleChangeGuardTest {

    private final TitleChangeGuard guard = new TitleChangeGuard();

    private final UUID targetTaskId = UUID.randomUUID();

    private TaskContextWindow windowWithTask(String title) {
        return new TaskContextWindow(
                "T1 · " + title,
                Map.of("T1", targetTaskId),
                Map.of("T1", title)
        );
    }

    private ProposedAction updateTitleAction(String newTitle) {
        return new ProposedAction(1, AssistantActionType.UPDATE, targetTaskId,
                Map.of("title", newTitle), "Переименовать — " + newTitle, false);
    }

    @Test
    void filter_dropsRenameWhenCurrentTitleNotMentioned() {
        var window = windowWithTask("Отправить материалы заказчику");
        var action = updateTitleAction("ложному варнингу");

        var result = guard.filter(List.of(action), window, "Поменять название ложному варнингу");

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().get(0)).contains("Отправить материалы заказчику");
    }

    @Test
    void filter_keepsRenameWhenCurrentTitleMentioned() {
        var window = windowWithTask("купить молоко");
        var action = updateTitleAction("купить молоко и хлеб");

        var result = guard.filter(List.of(action), window,
                "переименуй \"купить молоко\" в \"купить молоко и хлеб\"");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0)).isEqualTo(action);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_ignoresNonTitleUpdate() {
        var window = windowWithTask("купить молоко");
        var action = new ProposedAction(1, AssistantActionType.UPDATE, targetTaskId,
                Map.of("priority", "HIGH"), "Изменить приоритет", false);

        var result = guard.filter(List.of(action), window, "подними приоритет");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0)).isEqualTo(action);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_ignoresNonUpdateActions() {
        var window = windowWithTask("купить молоко");
        var action = new ProposedAction(1, AssistantActionType.COMPLETE, targetTaskId,
                Map.of("task_ref", "T1"), "Закрыть — купить молоко", false);

        var result = guard.filter(List.of(action), window, "закрой задачу");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_isCaseAndPunctuationInsensitive() {
        var window = windowWithTask("Купить, молоко!");
        var action = updateTitleAction("купить молоко и хлеб");

        var result = guard.filter(List.of(action), window, "КУПИТЬ МОЛОКО поменяй на купить молоко и хлеб");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_renumbersRemainingActions() {
        var window = windowWithTask("Отправить материалы заказчику");
        var dropped = updateTitleAction("ложному варнингу");
        var kept = new ProposedAction(2, AssistantActionType.COMPLETE, targetTaskId,
                Map.of("task_ref", "T1"), "Закрыть — что-то", false);

        var result = guard.filter(List.of(dropped, kept), window, "Поменять название ложному варнингу, и закрой");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).ordinal()).isEqualTo(1);
        assertThat(result.actions().get(0).payload()).isEqualTo(kept.payload());
    }
}
