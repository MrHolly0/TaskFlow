package ru.taskflow.app.experiment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Одна строка датасета Б1. Формат согласован в ТЗ (docs/vkr/тз-агенту-сбор-данных.md),
 * но точный список полей — рабочий контракт этого файла, а не цитата исходного
 * запроса: он не был доступен агенту дословно, состав полей выведен из требований
 * Б2 (что нужно записать с каждого обращения) и Б1 (что нужно в датасете, чтобы
 * это можно было проверить). Если реальный файл датасета придёт в другом виде —
 * это первое, что стоит свести.
 *
 * setupTasks.ref — локальный идентификатор внутри строки, не связан ни с чем
 * снаружи: ExperimentRunner создаёт по нему настоящую задачу перед обращением
 * и подставляет полученный UUID в ExpectedAction.targetRef при сверке.
 */
public record DatasetRow(
        String id,
        String category,
        String text,
        @JsonProperty("input_kind") String inputKind,
        @JsonProperty("setup_tasks") List<SetupTaskSpec> setupTasks,
        ExpectedOutcome expected,
        String notes
) {
    public DatasetRow {
        if (setupTasks == null) {
            setupTasks = List.of();
        }
    }
}
