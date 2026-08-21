package ru.taskflow.app.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Сырые результаты — CSV и JSON отдельными файлами, как требует форма сдачи.
 * CSV собирается через reflection по компонентам record: одно место истины
 * для набора колонок, не разъезжается с ExperimentRunResult при правках.
 */
public final class ExperimentOutputWriter {

    private ExperimentOutputWriter() {}

    public static void writeCsv(List<ExperimentRunResult> results, Path file) {
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Нечего записывать — пустой список результатов");
        }
        RecordComponent[] components = ExperimentRunResult.class.getRecordComponents();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(components[i].getName());
        }
        sb.append('\n');

        for (ExperimentRunResult result : results) {
            for (int i = 0; i < components.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(csvCell(value(result, components[i])));
            }
            sb.append('\n');
        }

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void writeJson(List<ExperimentRunResult> results, Path file) {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), results);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object value(ExperimentRunResult result, RecordComponent component) {
        try {
            return component.getAccessor().invoke(result);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось прочитать поле " + component.getName(), e);
        }
    }

    private static String csvCell(Object value) {
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
