package ru.taskflow.app.experiment;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActionMatcherTest {

    private final ActionMatcher matcher = new ActionMatcher();
    private final LocalDate today = LocalDate.of(2026, 8, 21);
    private final ZoneId zone = ZoneId.of("Europe/Moscow");
    private final UUID milkTaskId = UUID.randomUUID();

    private ProposedAction create(String title, String deadline) {
        Map<String, Object> payload = deadline == null
                ? Map.of("title", title)
                : Map.of("title", title, "deadline", deadline);
        return new ProposedAction(1, AssistantActionType.CREATE, null, payload, "Создать — " + title, true);
    }

    private ProposedAction complete(UUID targetId, String summary) {
        return new ProposedAction(1, AssistantActionType.COMPLETE, targetId, Map.of("task_ref", "T1"), summary, true);
    }

    private ExpectedAction expectedCreate(String titleContains, Integer offsetDays, Integer hour) {
        return new ExpectedAction("CREATE", titleContains, null, offsetDays, hour, null, null);
    }

    private ExpectedOutcome outcome(int count, ExpectedAction... actions) {
        return new ExpectedOutcome(count, false, List.of(actions));
    }

    @Test
    void match_countsExactMatchAsFullyCorrect() {
        var actual = List.of(create("купить молоко", "2026-08-22T18:00:00+03:00"));
        var expected = outcome(1, expectedCreate("молоко", 1, 18));

        var result = matcher.match(actual, expected, Map.of(), today, zone);

        assertThat(result.countCorrect()).isTrue();
        assertThat(result.fullyCorrect()).isTrue();
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.missingCount()).isZero();
        assertThat(result.extraCount()).isZero();
    }

    @Test
    void match_flagsWrongDateAsAttributeMismatchNotMissing() {
        var actual = List.of(create("купить молоко", "2026-08-21T18:00:00+03:00"));
        var expected = outcome(1, expectedCreate("молоко", 1, 18));

        var result = matcher.match(actual, expected, Map.of(), today, zone);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.fullyCorrect()).isFalse();
        assertThat(result.matched().getFirst().attributeMismatches()).anyMatch(m -> m.contains("дата"));
    }

    @Test
    void match_treatsUnmatchedActualActionAsExtra() {
        var actual = List.of(create("купить молоко", null), create("позвонить маме", null));
        var expected = outcome(1, expectedCreate("молоко", null, null));

        var result = matcher.match(actual, expected, Map.of(), today, zone);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.extraCount()).isEqualTo(1);
        assertThat(result.extraDescriptions().getFirst()).contains("позвонить маме");
        assertThat(result.countCorrect()).isFalse();
    }

    @Test
    void match_treatsUnfulfilledExpectationAsMissing() {
        var actual = List.<ProposedAction>of();
        var expected = outcome(1, expectedCreate("молоко", null, null));

        var result = matcher.match(actual, expected, Map.of(), today, zone);

        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.missingDescriptions().getFirst()).contains("молоко");
    }

    @Test
    void match_resolvesTargetRefToRealTaskId() {
        var actual = List.of(complete(milkTaskId, "Закрыть — купить молоко"));
        var expected = outcome(1, new ExpectedAction("COMPLETE", null, "S1", null, null, null, null));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), today, zone);

        assertThat(result.fullyCorrect()).isTrue();
    }

    @Test
    void match_rejectsCompleteOnWrongTarget() {
        UUID otherTaskId = UUID.randomUUID();
        var actual = List.of(complete(otherTaskId, "Закрыть — купить хлеб"));
        var expected = outcome(1, new ExpectedAction("COMPLETE", null, "S1", null, null, null, null));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), today, zone);

        assertThat(result.matchedCount()).isZero();
        assertThat(result.missingCount()).isEqualTo(1);
        assertThat(result.extraCount()).isEqualTo(1);
    }

    @Test
    void match_negativeCaseWithNoExpectedAndNoActualIsFullyCorrect() {
        var result = matcher.match(List.of(), outcome(0), Map.of(), today, zone);

        assertThat(result.fullyCorrect()).isTrue();
        assertThat(result.countCorrect()).isTrue();
    }

    @Test
    void match_negativeCaseFlagsUnexpectedActionAsExtra() {
        var actual = List.of(create("покажи задачи на завтра", null));
        var result = matcher.match(actual, outcome(0), Map.of(), today, zone);

        assertThat(result.extraCount()).isEqualTo(1);
        assertThat(result.countCorrect()).isFalse();
    }

    @Test
    void match_checksPriorityAndGroupWhenExpected() {
        var actual = List.of(new ProposedAction(1, AssistantActionType.CREATE, null,
                Map.of("title", "купить корм", "priority", "LOW", "group", "Покупки"),
                "Создать — купить корм", true));
        var expected = outcome(1, new ExpectedAction("CREATE", "корм", null, null, null, "HIGH", "Покупки"));

        var result = matcher.match(actual, expected, Map.of(), today, zone);

        assertThat(result.matched().getFirst().attributeMismatches()).anyMatch(m -> m.contains("приоритет"));
        assertThat(result.matched().getFirst().attributeMismatches()).noneMatch(m -> m.contains("группа"));
    }

    // --- Блок В: время напоминания ---

    private ProposedAction remind(UUID targetId, String reminderAt) {
        Map<String, Object> payload = reminderAt == null ? Map.of() : Map.of("reminder_at", reminderAt);
        return new ProposedAction(1, AssistantActionType.REMIND, targetId, payload, "Напомнить — задача", true);
    }

    @Test
    void match_countsExactReminderTimeAsFullyCorrect() {
        var actual = List.of(remind(milkTaskId, "2026-08-22T09:00:00+03:00"));
        var expected = outcome(1, new ExpectedAction("remind", null, "S1", null, null, null, null, 1, 9, null));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), today, zone);

        assertThat(result.fullyCorrect()).isTrue();
    }

    @Test
    void match_flagsWrongReminderHourAsMismatch() {
        var actual = List.of(remind(milkTaskId, "2026-08-22T10:00:00+03:00"));
        var expected = outcome(1, new ExpectedAction("remind", null, "S1", null, null, null, null, 1, 9, null));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), today, zone);

        assertThat(result.fullyCorrect()).isFalse();
        assertThat(result.matched().getFirst().attributeMismatches()).anyMatch(m -> m.contains("час напоминания"));
    }

    @Test
    void match_verifiesOffsetFromTargetTaskRealDeadline() {
        // Реальный дедлайн, не продекларированный в датасете offsetDays/Hour —
        // задача заведена "на сейчас + N дней", минуты/секунды не круглые.
        var targetDeadline = OffsetDateTime.parse("2026-08-22T14:37:00+03:00");
        var actual = List.of(remind(milkTaskId, "2026-08-22T14:07:00+03:00"));
        var expected = outcome(1, new ExpectedAction("remind", null, "S1", null, null, null, null, null, null, 30));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), Map.of("S1", targetDeadline), today, zone);

        assertThat(result.fullyCorrect()).isTrue();
    }

    @Test
    void match_flagsWrongOffsetFromTargetDeadline() {
        var targetDeadline = OffsetDateTime.parse("2026-08-22T14:37:00+03:00");
        var actual = List.of(remind(milkTaskId, "2026-08-22T13:00:00+03:00"));
        var expected = outcome(1, new ExpectedAction("remind", null, "S1", null, null, null, null, null, null, 30));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), Map.of("S1", targetDeadline), today, zone);

        assertThat(result.fullyCorrect()).isFalse();
        assertThat(result.matched().getFirst().attributeMismatches()).anyMatch(m -> m.contains("отступ от срока"));
    }

    @Test
    void match_reminderOffsetToleratesSubMinuteJitterFromSetupCreation() {
        // Дедлайн создан на "сейчас + N дней" — секунды не круглые. Модель видит
        // окно контекста, отрендеренное до минуты, и не может их угадать —
        // сверка не должна наказывать за то, чего модели не видно.
        var targetDeadline = OffsetDateTime.parse("2026-08-22T14:37:42+03:00");
        var actual = List.of(remind(milkTaskId, "2026-08-22T14:07:00+03:00"));
        var expected = outcome(1, new ExpectedAction("remind", null, "S1", null, null, null, null, null, null, 30));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), Map.of("S1", targetDeadline), today, zone);

        assertThat(result.fullyCorrect()).isTrue();
    }

    @Test
    void match_flagsMissingTargetDeadlineForOffsetCheck() {
        var actual = List.of(remind(milkTaskId, "2026-08-22T14:07:00+03:00"));
        var expected = outcome(1, new ExpectedAction("remind", null, "S1", null, null, null, null, null, null, 30));

        // Дедлайн цели не передан — сверить нечем.
        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), Map.of(), today, zone);

        assertThat(result.fullyCorrect()).isFalse();
        assertThat(result.matched().getFirst().attributeMismatches()).anyMatch(m -> m.contains("не удалось сверить"));
    }

    @Test
    void match_defaultOverloadIgnoresReminderTimeChecks() {
        // Старый 5-аргументный вызов (без setupRefToDeadline) по-прежнему
        // работает — совместимость со всеми вызовами до блока В.
        var actual = List.of(remind(milkTaskId, "2026-08-22T09:00:00+03:00"));
        var expected = outcome(1, new ExpectedAction("remind", null, "S1", null, null, null, null, null, null, 30));

        var result = matcher.match(actual, expected, Map.of("S1", milkTaskId), today, zone);

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.matched().getFirst().attributeMismatches()).anyMatch(m -> m.contains("не удалось сверить"));
    }
}
