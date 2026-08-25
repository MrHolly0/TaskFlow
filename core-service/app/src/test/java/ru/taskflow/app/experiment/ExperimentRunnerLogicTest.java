package ru.taskflow.app.experiment;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Чистая логика ExperimentRunner, вынесенная в static-методы ради теста без
 * Spring-контекста и живой модели — resolveFullyCorrect отвечает на запрос
 * владельца: сверка при ambiguous=true должна смотреть не только на состав
 * действий, но и на факт предложенного выбора (choiceOffered).
 */
class ExperimentRunnerLogicTest {

    private final ActionMatcher matcher = new ActionMatcher();
    private final LocalDate today = LocalDate.of(2026, 8, 25);
    private final ZoneId zone = ZoneId.of("Europe/Moscow");

    private ActionMatcher.MatchResult matchingResult() {
        // Состав совпадает полностью — единственное, что здесь варьируется
        // в тестах, это choiceOffered/expectedAmbiguous снаружи.
        return matcher.match(List.of(), new ExpectedOutcome(0, false, List.of()), Map.of(), today, zone);
    }

    @Test
    void resolveFullyCorrect_ignoresChoiceOfferedWhenNotExpectedAmbiguous() {
        var match = matchingResult();

        assertThat(ExperimentRunner.resolveFullyCorrect(match, false, false)).isTrue();
        assertThat(ExperimentRunner.resolveFullyCorrect(match, false, true)).isTrue();
    }

    @Test
    void resolveFullyCorrect_requiresChoiceOfferedWhenExpectedAmbiguous() {
        var match = matchingResult();

        assertThat(ExperimentRunner.resolveFullyCorrect(match, true, true)).isTrue();
    }

    // Главный случай из запроса владельца: состав действий совпал (два
    // варианта проставлены как обычный пакет), но выбор не был предложен —
    // это не то же самое поведение, что ожидалось при двоякости.
    @Test
    void resolveFullyCorrect_failsWhenAmbiguousExpectedButNoChoiceOffered() {
        var match = matchingResult();

        assertThat(ExperimentRunner.resolveFullyCorrect(match, true, false)).isFalse();
    }

    @Test
    void resolveFullyCorrect_neverPassesWhenActionContentAlreadyWrong() {
        var wrongMatch = matcher.match(
                List.of(),
                new ExpectedOutcome(1, true, List.of(new ExpectedAction("create", "молоко", null, null, null, null, null))),
                Map.of(), today, zone);

        assertThat(ExperimentRunner.resolveFullyCorrect(wrongMatch, true, true)).isFalse();
    }
}
