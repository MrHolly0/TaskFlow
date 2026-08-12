package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateGuardTest {

    private final DuplicateGuard guard = new DuplicateGuard();

    private final UUID catFoodTaskId = UUID.randomUUID();

    private TaskContextWindow windowWithCatFoodTask() {
        return new TaskContextWindow(
                "T9 · Купить корм коту",
                Map.of("T9", catFoodTaskId),
                Map.of("T9", "Купить корм коту")
        );
    }

    private ProposedAction createAction(int ordinal, String title) {
        return new ProposedAction(ordinal, AssistantActionType.CREATE, null,
                Map.of("title", title), "Создать — " + title, false);
    }

    @Test
    void filter_dropsExactDuplicate() {
        var action = createAction(1, "Купить корм коту");

        var result = guard.filter(List.of(action), windowWithCatFoodTask());

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().get(0)).contains("T9");
    }

    @Test
    void filter_dropsDuplicateIgnoringCaseAndPunctuation() {
        var action = createAction(1, "купить корм коту!");

        var result = guard.filter(List.of(action), windowWithCatFoodTask());

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().get(0)).contains("T9");
    }

    @Test
    void filter_keepsDifferentTask() {
        var action = createAction(1, "Купить корм собаке");

        var result = guard.filter(List.of(action), windowWithCatFoodTask());

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0)).isEqualTo(action);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_keepsBelowThreshold() {
        var action = createAction(1, "Купить билеты на концерт в субботу");

        var result = guard.filter(List.of(action), windowWithCatFoodTask());

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0)).isEqualTo(action);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_ignoresNonCreateActions() {
        UUID taskId = UUID.randomUUID();
        var action = new ProposedAction(1, AssistantActionType.COMPLETE, taskId,
                Map.of("task_ref", "T9"), "Закрыть — Купить корм коту", false);

        var result = guard.filter(List.of(action), windowWithCatFoodTask());

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0)).isEqualTo(action);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_renumbersRemainingActions() {
        var first = createAction(1, "Купить билеты на концерт в субботу");
        var duplicate = createAction(2, "Купить корм коту");
        var third = new ProposedAction(3, AssistantActionType.COMPLETE, UUID.randomUUID(),
                Map.of("task_ref", "T9"), "Закрыть — что-то", false);

        var result = guard.filter(List.of(first, duplicate, third), windowWithCatFoodTask());

        assertThat(result.actions()).hasSize(2);
        assertThat(result.actions().get(0).ordinal()).isEqualTo(1);
        assertThat(result.actions().get(0).payload()).isEqualTo(first.payload());
        assertThat(result.actions().get(1).ordinal()).isEqualTo(2);
        assertThat(result.actions().get(1).payload()).isEqualTo(third.payload());
        assertThat(result.rejections()).hasSize(1);
    }

    @Test
    void filter_handlesEmptyWindow() {
        var action = createAction(1, "Купить корм коту");
        var emptyWindow = new TaskContextWindow("Сейчас активных задач нет", Map.of(), Map.of());

        var result = guard.filter(List.of(action), emptyWindow);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0)).isEqualTo(action);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_toleratesRepeatedWordInProposedTitle() {
        var action = createAction(1, "сходить сходить в магазин");

        var result = guard.filter(List.of(action), windowWithCatFoodTask());

        assertThat(result.actions()).hasSize(1);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_toleratesRepeatedWordInWindowTaskTitle() {
        var action = createAction(1, "позвонить маме");
        var window = new TaskContextWindow(
                "T3 · купить хлеб и хлеб",
                Map.of("T3", UUID.randomUUID()),
                Map.of("T3", "купить хлеб и хлеб")
        );

        var result = guard.filter(List.of(action), window);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_dropsActionRepeatedFromEarlierPass() {
        var alreadyProposed = List.of(createAction(1, "Купить корм коту"));
        var repeated = createAction(1, "купить корм коту!");
        var emptyWindow = new TaskContextWindow("Сейчас активных задач нет", Map.of(), Map.of());

        var result = guard.filter(List.of(repeated), emptyWindow, alreadyProposed);

        assertThat(result.actions()).isEmpty();
        assertThat(result.rejections()).hasSize(1);
        assertThat(result.rejections().get(0)).contains("уже предложено");
    }

    @Test
    void filter_keepsDifferentCreatesAcrossTwoPasses() {
        var alreadyProposed = List.of(createAction(1, "Купить корм коту"));
        var different = createAction(1, "Записаться к стоматологу");
        var emptyWindow = new TaskContextWindow("Сейчас активных задач нет", Map.of(), Map.of());

        var result = guard.filter(List.of(different), emptyWindow, alreadyProposed);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0)).isEqualTo(different);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void filter_crossPassDropDoesNotBreakNumbering() {
        var alreadyProposed = List.of(createAction(1, "Купить корм коту"));
        var repeated = createAction(1, "купить корм коту");
        var kept = createAction(2, "Записаться к стоматологу");
        var emptyWindow = new TaskContextWindow("Сейчас активных задач нет", Map.of(), Map.of());

        var result = guard.filter(List.of(repeated, kept), emptyWindow, alreadyProposed);

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).ordinal()).isEqualTo(1);
        assertThat(result.actions().get(0).payload()).isEqualTo(kept.payload());
        assertThat(result.rejections()).hasSize(1);
    }
}
