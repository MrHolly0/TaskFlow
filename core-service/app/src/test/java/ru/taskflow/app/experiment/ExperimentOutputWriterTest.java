package ru.taskflow.app.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExperimentOutputWriterTest {

    private ExperimentRunResult sampleResult(String id) {
        return new ExperimentRunResult(id, "SINGLE_CREATE", 1, "купить молоко, \"завтра\"",
                1, 1, true, true, 0, 0, 0, "CREATE", "", "", "", "CREATE:1/1",
                1200, 900, ExperimentRunResult.NOT_MEASURED, ExperimentRunResult.NOT_MEASURED,
                150, 40, 1, false, 1,
                0, "",
                false, false, false, false, null,
                "PENDING", null, false, null);
    }

    @Test
    void writeCsv_headerMatchesRecordComponentNames(@TempDir Path dir) {
        Path file = dir.resolve("results.csv");

        ExperimentOutputWriter.writeCsv(List.of(sampleResult("R001")), file);

        String header = readLines(file).getFirst();
        assertThat(header).isEqualTo(String.join(",",
                java.util.Arrays.stream(ExperimentRunResult.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList()));
    }

    @Test
    void writeCsv_quotesValuesContainingCommaOrQuotes(@TempDir Path dir) {
        Path file = dir.resolve("results.csv");

        ExperimentOutputWriter.writeCsv(List.of(sampleResult("R001")), file);

        List<String> lines = readLines(file);
        assertThat(lines.get(1)).contains("\"купить молоко, \"\"завтра\"\"\"");
    }

    @Test
    void writeCsv_oneDataLinePerResult(@TempDir Path dir) {
        Path file = dir.resolve("results.csv");

        ExperimentOutputWriter.writeCsv(List.of(sampleResult("R001"), sampleResult("R002")), file);

        assertThat(readLines(file)).hasSize(3);
    }

    @Test
    void writeJson_roundTripsAllFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("results.json");
        var original = sampleResult("R001");

        ExperimentOutputWriter.writeJson(List.of(original), file);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restored = mapper.readValue(file.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<List<ExperimentRunResult>>() {});
        assertThat(restored).containsExactly(original);
    }

    // declineReason — закрытый список причин отказа (question/chitchat/unclear),
    // заводился ровно ради разбивки no_action на доли (пункт 3); пусто, если
    // отказа не было — sampleResult() уже проверяет этот (пустой) случай выше.
    private ExperimentRunResult sampleDeclinedResult(String id) {
        return new ExperimentRunResult(id, "NEGATIVE", 1, "спасибо",
                0, 0, true, true, 0, 0, 0, "", "", "", "", "",
                1200, 900, ExperimentRunResult.NOT_MEASURED, ExperimentRunResult.NOT_MEASURED,
                1800, 30, 1, false, 0,
                0, "",
                false, false, false, false, null,
                "DECLINED", "CHITCHAT", false, null);
    }

    @Test
    void writeJson_roundTripsDeclineReasonWhenPresent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("results.json");
        var original = sampleDeclinedResult("R003");

        ExperimentOutputWriter.writeJson(List.of(original), file);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var restored = mapper.readValue(file.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<List<ExperimentRunResult>>() {});
        assertThat(restored).containsExactly(original);
        assertThat(restored.getFirst().declineReason()).isEqualTo("CHITCHAT");
    }

    @Test
    void writeCsv_includesDeclineReasonColumn(@TempDir Path dir) {
        Path file = dir.resolve("results.csv");

        ExperimentOutputWriter.writeCsv(List.of(sampleDeclinedResult("R003")), file);

        List<String> header = List.of(readLines(file).getFirst().split(","));
        int declineReasonIndex = header.indexOf("declineReason");
        assertThat(declineReasonIndex).isNotNegative();
        assertThat(readLines(file).get(1).split(",")[declineReasonIndex]).isEqualTo("CHITCHAT");
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
