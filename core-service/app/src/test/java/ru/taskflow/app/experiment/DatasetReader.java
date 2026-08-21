package ru.taskflow.app.experiment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Читает датасет из JSON-файла — формат, а не число строк, определяет объём
 * прогона: 50 и 150 реплик проходят один и тот же код без переключателей.
 */
public final class DatasetReader {

    private DatasetReader() {}

    public static List<DatasetRow> read(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Файл датасета не найден: " + file.toAbsolutePath()
                    + ". Путь берётся из системного свойства experiment.dataset "
                    + "(-Dexperiment.dataset=/path/to/dataset.json) или переменной окружения EXPERIMENT_DATASET.");
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            List<DatasetRow> rows = mapper.readValue(file.toFile(), new TypeReference<List<DatasetRow>>() {});
            validate(rows, file);
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось разобрать датасет " + file.toAbsolutePath(), e);
        }
    }

    private static void validate(List<DatasetRow> rows, Path file) {
        if (rows.isEmpty()) {
            throw new IllegalStateException("Датасет " + file.toAbsolutePath() + " пуст");
        }
        var seenIds = new java.util.HashSet<String>();
        for (DatasetRow row : rows) {
            if (row.id() == null || row.id().isBlank()) {
                throw new IllegalStateException("Строка датасета без id: " + row);
            }
            if (!seenIds.add(row.id())) {
                throw new IllegalStateException("Повторяющийся id в датасете: " + row.id());
            }
            if (row.text() == null || row.text().isBlank()) {
                throw new IllegalStateException("Строка " + row.id() + " без текста реплики");
            }
            if (row.expected() == null) {
                throw new IllegalStateException("Строка " + row.id() + " без ожидаемого результата (expected)");
            }
        }
    }
}
