package ru.taskflow.app.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Считает агрегаты Б3 из уже собранного results-*.json — без единого нового
 * обращения к модели (ТЗ: "считать из собранного, не отдельными прогонами").
 * Путь к цене за миллион токенов — параметр вызова
 * (-Dexperiment.priceIn / -Dexperiment.priceOut), не константа в коде: цена
 * определяется по прайсу поставщика на момент отчёта, с датой и ссылкой,
 * а не зашивается в сборку.
 * <p>
 * Не тег live: сети не касается, только читает файл с диска. Пропускается,
 * если результатов ещё нет — на чужой машине без приватных данных эксперимента.
 */
class AggregateReportPrinter {

    @Test
    void printLatestRunAggregate() throws IOException {
        Path resultsDir = RepoPaths.resolveFromRepoRoot(
                System.getProperty("experiment.resultsDir", "docs/vkr/dataset/результаты"));
        Assumptions.assumeTrue(Files.isDirectory(resultsDir),
                "Каталог результатов " + resultsDir + " не найден — отчёт пропущен");

        Optional<Path> latestJson = Files.list(resultsDir)
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .max(Comparator.comparing(p -> p.getFileName().toString()));
        Assumptions.assumeTrue(latestJson.isPresent(), "Нет ни одного results-*.json в " + resultsDir);

        double priceIn = Double.parseDouble(System.getProperty("experiment.priceIn", "0"));
        double priceOut = Double.parseDouble(System.getProperty("experiment.priceOut", "0"));

        ObjectMapper mapper = new ObjectMapper();
        List<ExperimentRunResult> results = mapper.readValue(latestJson.get().toFile(),
                new TypeReference<List<ExperimentRunResult>>() {});

        var report = ExperimentAggregator.aggregate(results, priceIn, priceOut);
        var ambiguity = ExperimentAggregator.ambiguitySubset(results);

        System.out.println("=== " + latestJson.get().getFileName() + " (" + results.size() + " строк) ===");
        System.out.printf(Locale.ROOT, "Чистых обращений: %d из %d (%.1f%%) — остальные агрегаты посчитаны только по ним%n",
                report.cleanCount(), report.totalRequested(), 100.0 * report.cleanFraction());
        System.out.printf(Locale.ROOT, "Полнота (доля верно выделенных действий): %.3f%n", report.recall());
        System.out.printf(Locale.ROOT, "Точность: %.3f%n", report.precision());
        System.out.printf(Locale.ROOT, "Доля ошибок в количестве действий: %.3f%n", report.countErrorRate());
        System.out.printf(Locale.ROOT, "Полная корректность ответа: %.3f%n", report.fullCorrectnessRate());
        System.out.println("По атрибутам (несовпадений): " + report.attributeMismatchCounts());
        System.out.println("По операциям (верно/ожидалось):");
        for (var op : report.byOperation()) {
            System.out.printf(Locale.ROOT, "  %-10s %d/%d (%.3f)%n", op.type(), op.matched(), op.expected(), op.accuracy());
        }
        System.out.printf(Locale.ROOT,
                "Задержки, мс — полное время: min=%d p50=%.0f p90=%.0f p95=%.0f max=%d (n=%d)%n",
                report.totalLatency().min(), report.totalLatency().median(), report.totalLatency().p90(),
                report.totalLatency().p95(), report.totalLatency().max(), report.totalLatency().sampleSize());
        System.out.printf(Locale.ROOT,
                "Задержки, мс — первый проход: min=%d p50=%.0f p90=%.0f p95=%.0f max=%d (n=%d)%n",
                report.firstPassLatency().min(), report.firstPassLatency().median(), report.firstPassLatency().p90(),
                report.firstPassLatency().p95(), report.firstPassLatency().max(), report.firstPassLatency().sampleSize());
        System.out.printf(Locale.ROOT,
                "Задержки, мс — второй проход: min=%d p50=%.0f p90=%.0f p95=%.0f max=%d (n=%d)%n",
                report.secondPassLatency().min(), report.secondPassLatency().median(), report.secondPassLatency().p90(),
                report.secondPassLatency().p95(), report.secondPassLatency().max(), report.secondPassLatency().sampleSize());
        System.out.printf(Locale.ROOT, "Токены — среднее вход/выход: %.0f / %.0f%n",
                report.avgInputTokens(), report.avgOutputTokens());
        System.out.printf(Locale.ROOT, "Токены всего — медиана: %.0f, 90-й процентиль: %.0f%n",
                report.medianTokensTotal(), report.p90TokensTotal());
        if (priceIn > 0 || priceOut > 0) {
            System.out.printf(Locale.ROOT, "Стоимость обращения: $%.6f, 100: $%.4f, 1000: $%.2f%n",
                    report.cost().costPerRequestUsd(), report.cost().costPer100Usd(), report.cost().costPer1000Usd());
        }
        System.out.println();
        System.out.println("Подмножество AMBIGUOUS: " + ambiguity);
    }
}
