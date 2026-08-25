package ru.taskflow.app.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Считает агрегаты Б3 из уже собранного List&lt;ExperimentRunResult&gt; — без
 * единого нового обращения к модели, как того требует ТЗ (квота истрачена на
 * сбор, не на пересчёт).
 * <p>
 * Точность/полнота посчитаны, потому что ложноположительное определено
 * механически в ActionMatcher (см. его javadoc) — иначе, по тому же ТЗ, их
 * не следовало бы считать вовсе.
 */
public final class ExperimentAggregator {

    private static final Pattern ATTRIBUTE_PREFIX = Pattern.compile("^(дата|час|приоритет|группа): ");

    private ExperimentAggregator() {}

    public record LatencyStats(long min, long max, double median, double p90, double p95, int sampleSize) {}

    public record OperationStats(String type, int matched, int expected) {
        public double accuracy() {
            return expected == 0 ? 1.0 : (double) matched / expected;
        }
    }

    public record AmbiguitySubsetStats(
            int totalAmbiguousRows,
            int modelMarkedCount,
            int notMarkedCount,
            int deterministicCaughtCount,
            int choiceOfferedCount,
            int wrongPrimaryChoiceCount
    ) {}

    public record CostEstimate(
            double pricePerMillionInputTokens,
            double pricePerMillionOutputTokens,
            double avgInputTokens,
            double avgOutputTokens,
            double costPerRequestUsd,
            double costPer100Usd,
            double costPer1000Usd
    ) {
        public double monthlyCostUsd(int requestsPerDay) {
            return costPerRequestUsd * requestsPerDay * 30;
        }
    }

    public record AggregateReport(
            int totalRequested,
            int cleanCount,
            double cleanFraction,
            double recallByTaskCount,
            double countErrorRate,
            double fullCorrectnessRate,
            double precision,
            double recall,
            Map<String, Integer> attributeMismatchCounts,
            List<OperationStats> byOperation,
            LatencyStats totalLatency,
            LatencyStats firstPassLatency,
            LatencyStats secondPassLatency,
            double avgInputTokens,
            double avgOutputTokens,
            double medianTokensTotal,
            double p90TokensTotal,
            CostEstimate cost
    ) {}

    /**
     * Деградировавшие обращения (llmFailed=true — статус FAILED или нулевой
     * расход токенов, см. ExperimentRunner.toResult) исключены из всех
     * агрегатов здесь и только здесь: не выдаём измерение вслепую за
     * результат. totalRequested/cleanCount/cleanFraction — чтобы отчёт всегда
     * показывал, на какой доле реально посчитаны остальные цифры.
     */
    public static AggregateReport aggregate(List<ExperimentRunResult> allResults,
                                             double pricePerMillionInputTokens,
                                             double pricePerMillionOutputTokens) {
        if (allResults.isEmpty()) {
            throw new IllegalArgumentException("Нечего агрегировать — пустой список результатов");
        }
        List<ExperimentRunResult> results = allResults.stream().filter(r -> !r.llmFailed()).toList();
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Все " + allResults.size() + " обращений деградировали — агрегировать нечего");
        }

        int totalExpected = results.stream().mapToInt(ExperimentRunResult::expectedActionCount).sum();
        int totalActual = results.stream().mapToInt(ExperimentRunResult::actualActionCount).sum();
        int totalMatched = totalExpected - totalMissing(results);
        int countCorrect = (int) results.stream().filter(ExperimentRunResult::countCorrect).count();
        int fullyCorrect = (int) results.stream().filter(ExperimentRunResult::fullyCorrect).count();

        double recall = totalExpected == 0 ? Double.NaN : (double) totalMatched / totalExpected;
        double precision = totalActual == 0 ? Double.NaN : (double) totalMatched / totalActual;

        return new AggregateReport(
                allResults.size(),
                results.size(),
                (double) results.size() / allResults.size(),
                recall,
                1.0 - (double) countCorrect / results.size(),
                (double) fullyCorrect / results.size(),
                precision,
                recall,
                attributeMismatchCounts(results),
                byOperation(results),
                latencyStats(results, ExperimentRunResult::totalLatencyMs),
                latencyStats(results, ExperimentRunResult::firstPassLatencyMs),
                latencyStats(results, ExperimentRunResult::secondPassLatencyMs),
                results.stream().mapToInt(ExperimentRunResult::inputTokens).average().orElse(0),
                results.stream().mapToInt(ExperimentRunResult::outputTokens).average().orElse(0),
                percentile(tokenTotals(results), 0.5),
                percentile(tokenTotals(results), 0.9),
                costEstimate(results, pricePerMillionInputTokens, pricePerMillionOutputTokens)
        );
    }

    /** Тот же фильтр деградаций, что и в aggregate() — иначе провал провайдера ошибочно засчитался бы «двоякость не поймана». */
    public static AmbiguitySubsetStats ambiguitySubset(List<ExperimentRunResult> allResults) {
        List<ExperimentRunResult> ambiguous = allResults.stream()
                .filter(r -> !r.llmFailed())
                .filter(r -> "AMBIGUOUS".equals(r.category()))
                .toList();
        return new AmbiguitySubsetStats(
                ambiguous.size(),
                (int) ambiguous.stream().filter(ExperimentRunResult::modelMarkedAmbiguous).count(),
                (int) ambiguous.stream().filter(r -> !r.modelMarkedAmbiguous() && !r.deterministicAmbiguityDetected()).count(),
                (int) ambiguous.stream().filter(ExperimentRunResult::deterministicAmbiguityDetected).count(),
                (int) ambiguous.stream().filter(ExperimentRunResult::choiceOffered).count(),
                (int) ambiguous.stream().filter(r -> !r.choiceOffered() && !r.fullyCorrect()).count()
        );
    }

    private static int totalMissing(List<ExperimentRunResult> results) {
        return results.stream().mapToInt(ExperimentRunResult::missingCount).sum();
    }

    private static Map<String, Integer> attributeMismatchCounts(List<ExperimentRunResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("дата", 0);
        counts.put("час", 0);
        counts.put("приоритет", 0);
        counts.put("группа", 0);
        for (ExperimentRunResult result : results) {
            if (result.attributeMismatchDescriptions() == null || result.attributeMismatchDescriptions().isBlank()) {
                continue;
            }
            for (String part : result.attributeMismatchDescriptions().split("\\|")) {
                Matcher matcher = ATTRIBUTE_PREFIX.matcher(part.trim());
                if (matcher.find()) {
                    counts.merge(matcher.group(1), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static List<OperationStats> byOperation(List<ExperimentRunResult> results) {
        Map<String, int[]> matchedAndExpected = new TreeMap<>(); // [matched, expected]
        for (ExperimentRunResult result : results) {
            if (result.perTypeBreakdown() == null || result.perTypeBreakdown().isBlank()) {
                continue;
            }
            for (String part : result.perTypeBreakdown().split(";")) {
                String[] typeAndCounts = part.split(":");
                String[] fraction = typeAndCounts[1].split("/");
                matchedAndExpected.computeIfAbsent(typeAndCounts[0], k -> new int[2]);
                matchedAndExpected.get(typeAndCounts[0])[0] += Integer.parseInt(fraction[0]);
                matchedAndExpected.get(typeAndCounts[0])[1] += Integer.parseInt(fraction[1]);
            }
        }
        List<OperationStats> stats = new ArrayList<>();
        matchedAndExpected.forEach((type, counts) -> stats.add(new OperationStats(type, counts[0], counts[1])));
        return stats;
    }

    private static LatencyStats latencyStats(List<ExperimentRunResult> results,
                                              java.util.function.ToLongFunction<ExperimentRunResult> extractor) {
        List<Long> values = results.stream()
                .mapToLong(extractor)
                .filter(v -> v != ExperimentRunResult.NOT_MEASURED)
                .boxed()
                .sorted()
                .toList();
        if (values.isEmpty()) {
            return new LatencyStats(0, 0, 0, 0, 0, 0);
        }
        return new LatencyStats(values.getFirst(), values.getLast(),
                percentile(values, 0.5), percentile(values, 0.9), percentile(values, 0.95), values.size());
    }

    private static List<Long> tokenTotals(List<ExperimentRunResult> results) {
        return results.stream()
                .map(r -> (long) (r.inputTokens() + r.outputTokens()))
                .sorted()
                .toList();
    }

    /** Ближайший ранг — без интерполяции, достаточно для отчёта на сотнях наблюдений. */
    private static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private static CostEstimate costEstimate(List<ExperimentRunResult> results,
                                              double pricePerMillionInputTokens,
                                              double pricePerMillionOutputTokens) {
        double avgIn = results.stream().mapToInt(ExperimentRunResult::inputTokens).average().orElse(0);
        double avgOut = results.stream().mapToInt(ExperimentRunResult::outputTokens).average().orElse(0);
        double perRequest = avgIn / 1_000_000.0 * pricePerMillionInputTokens
                + avgOut / 1_000_000.0 * pricePerMillionOutputTokens;
        return new CostEstimate(pricePerMillionInputTokens, pricePerMillionOutputTokens, avgIn, avgOut,
                perRequest, perRequest * 100, perRequest * 1000);
    }
}
