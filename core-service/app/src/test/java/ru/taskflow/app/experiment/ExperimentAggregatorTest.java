package ru.taskflow.app.experiment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExperimentAggregatorTest {

    private ExperimentRunResult result(String category, int expected, int actual, boolean countCorrect,
                                        boolean fullyCorrect, int missing, int extra, String attrMismatches,
                                        String perTypeBreakdown, long total, long firstPass, long secondPass,
                                        int inTokens, int outTokens, boolean modelMarked, boolean deterministic,
                                        boolean choiceOffered) {
        return new ExperimentRunResult("R", category, 1, "текст",
                expected, actual, countCorrect, fullyCorrect, missing, extra, 0, "CREATE",
                "", "", attrMismatches, perTypeBreakdown,
                total, firstPass, secondPass, ExperimentRunResult.NOT_MEASURED,
                inTokens, outTokens, 1, false, actual,
                0, "",
                "AMBIGUOUS".equals(category), choiceOffered, modelMarked, deterministic, null,
                "PENDING", null, false, null);
    }

    private ExperimentRunResult degraded(String category, String id) {
        return new ExperimentRunResult(id, category, 1, "текст",
                1, 0, false, false, 1, 0, 0, "", "", "", "", "",
                ExperimentRunResult.NOT_MEASURED, ExperimentRunResult.NOT_MEASURED,
                ExperimentRunResult.NOT_MEASURED, ExperimentRunResult.NOT_MEASURED,
                0, 0,
                1, false, 0,
                0, "",
                "AMBIGUOUS".equals(category), false, false, false, null,
                "PENDING", null, true, "деградация: нулевой расход токенов");
    }

    @Test
    void aggregate_rejectsEmptyResults() {
        assertThatThrownBy(() -> ExperimentAggregator.aggregate(List.of(), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Живой дефект (эксперимент Б, 50×3 21.08.2026): исчерпание дневного
    // лимита токенов у поставщика возвращалось как формально успешный
    // пустой ответ — 64 из 150 обращений выглядели как обычный результат
    // при нулевом расходе токенов, и агрегаты были занижены смесью чистых
    // и деградировавших данных.
    @Test
    void aggregate_excludesDegradedResponsesFromAllStatistics() {
        var clean = result("SINGLE_CREATE", 1, 1, true, true, 0, 0, "", "CREATE:1/1",
                100, 100, 0, 500, 100, false, false, false);

        var report = ExperimentAggregator.aggregate(List.of(clean, degraded("SINGLE_CREATE", "R2")), 1, 1);

        assertThat(report.totalRequested()).isEqualTo(2);
        assertThat(report.cleanCount()).isEqualTo(1);
        assertThat(report.cleanFraction()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.fullCorrectnessRate()).isEqualTo(1.0);
    }

    @Test
    void aggregate_throwsWhenEveryResponseDegraded() {
        assertThatThrownBy(() -> ExperimentAggregator.aggregate(List.of(degraded("SINGLE_CREATE", "R1")), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregate_computesRecallAndPrecisionFromMatchedCounts() {
        var results = List.of(
                result("SINGLE_CREATE", 1, 1, true, true, 0, 0, "", "CREATE:1/1", 100, 100, 0, 500, 100, false, false, false),
                result("SINGLE_CREATE", 1, 0, false, false, 1, 0, "", "CREATE:0/1", 100, 100, 0, 500, 100, false, false, false),
                result("SINGLE_CREATE", 1, 2, false, false, 0, 1, "", "CREATE:1/1", 100, 100, 0, 500, 100, false, false, false)
        );

        var report = ExperimentAggregator.aggregate(results, 1, 1);

        // expected=3, matched=2 (1-й и 3-й), extra=1 → recall=2/3, precision=2/3
        assertThat(report.recall()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.precision()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.countErrorRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.fullCorrectnessRate()).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void aggregate_countsAttributeMismatchesByKind() {
        var results = List.of(
                result("DATE_TIME", 1, 1, true, false, 0, 0,
                        "дата: ожидалось 2026-08-22, получено 2026-08-21", "CREATE:1/1",
                        100, 100, 0, 500, 100, false, false, false),
                result("DATE_TIME", 1, 1, true, false, 0, 0,
                        "час: ожидалось 18, получено null | приоритет: ожидалось HIGH, получено MEDIUM", "CREATE:1/1",
                        100, 100, 0, 500, 100, false, false, false)
        );

        var report = ExperimentAggregator.aggregate(results, 1, 1);

        assertThat(report.attributeMismatchCounts()).containsEntry("дата", 1);
        assertThat(report.attributeMismatchCounts()).containsEntry("час", 1);
        assertThat(report.attributeMismatchCounts()).containsEntry("приоритет", 1);
        assertThat(report.attributeMismatchCounts()).containsEntry("группа", 0);
    }

    @Test
    void aggregate_sumsPerOperationBreakdownAcrossRows() {
        var results = List.of(
                result("MIXED_OPERATIONS", 2, 2, true, true, 0, 0, "", "COMPLETE:1/1;CREATE:1/1",
                        100, 100, 0, 500, 100, false, false, false),
                result("COMPLETE_CANCEL", 1, 0, false, false, 1, 0, "", "COMPLETE:0/1",
                        100, 100, 0, 500, 100, false, false, false)
        );

        var report = ExperimentAggregator.aggregate(results, 1, 1);

        var complete = report.byOperation().stream().filter(o -> o.type().equals("COMPLETE")).findFirst().orElseThrow();
        assertThat(complete.matched()).isEqualTo(1);
        assertThat(complete.expected()).isEqualTo(2);
        assertThat(complete.accuracy()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void aggregate_computesLatencyPercentilesExcludingUnmeasured() {
        var results = List.of(
                result("SINGLE_CREATE", 1, 1, true, true, 0, 0, "", "CREATE:1/1", 100, 80, 0, 500, 100, false, false, false),
                result("SINGLE_CREATE", 1, 1, true, true, 0, 0, "", "CREATE:1/1", 200, 150, 0, 500, 100, false, false, false),
                result("SINGLE_CREATE", 1, 1, true, true, 0, 0, "", "CREATE:1/1", 300, 250, 0, 500, 100, false, false, false)
        );

        var report = ExperimentAggregator.aggregate(results, 1, 1);

        assertThat(report.totalLatency().min()).isEqualTo(100);
        assertThat(report.totalLatency().max()).isEqualTo(300);
        assertThat(report.totalLatency().sampleSize()).isEqualTo(3);
    }

    @Test
    void aggregate_computesCostFromAverageTokensAndGivenPrice() {
        var results = List.of(
                result("SINGLE_CREATE", 1, 1, true, true, 0, 0, "", "CREATE:1/1", 100, 100, 0, 1_000_000, 500_000, false, false, false)
        );

        // $1 за миллион входных, $2 за миллион выходных — круглые числа для проверки арифметики
        var report = ExperimentAggregator.aggregate(results, 1.0, 2.0);

        assertThat(report.cost().costPerRequestUsd()).isCloseTo(1.0 + 1.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.cost().costPer100Usd()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(report.cost().monthlyCostUsd(10)).isCloseTo(2.0 * 10 * 30, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void ambiguitySubset_talliesOnlyAmbiguousCategoryRows() {
        var results = List.of(
                result("AMBIGUOUS", 2, 2, true, true, 0, 0, "", "COMPLETE:1/1;CREATE:1/1", 100, 100, 0, 500, 100, true, false, true),
                result("AMBIGUOUS", 1, 1, true, false, 0, 0, "", "COMPLETE:1/1", 100, 100, 0, 500, 100, false, true, true),
                result("AMBIGUOUS", 1, 1, true, false, 0, 0, "", "CREATE:1/1", 100, 100, 0, 500, 100, false, false, false),
                result("SINGLE_CREATE", 1, 1, true, true, 0, 0, "", "CREATE:1/1", 100, 100, 0, 500, 100, false, false, false)
        );

        var stats = ExperimentAggregator.ambiguitySubset(results);

        assertThat(stats.totalAmbiguousRows()).isEqualTo(3);
        assertThat(stats.modelMarkedCount()).isEqualTo(1);
        assertThat(stats.deterministicCaughtCount()).isEqualTo(1);
        assertThat(stats.notMarkedCount()).isEqualTo(1);
        assertThat(stats.choiceOfferedCount()).isEqualTo(2);
    }

    @Test
    void ambiguitySubset_excludesDegradedResponses() {
        var stats = ExperimentAggregator.ambiguitySubset(List.of(degraded("AMBIGUOUS", "R1")));

        assertThat(stats.totalAmbiguousRows()).isZero();
    }
}
