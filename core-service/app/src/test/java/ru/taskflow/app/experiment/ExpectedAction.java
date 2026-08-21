package ru.taskflow.app.experiment;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Ожидаемое действие для сверки с ProposedAction. Любое поле, кроме type,
 * может быть null — тогда это поле не проверяется у совпавшего действия
 * (составитель датасета не всегда может знать точный текст title или что
 * модель выберет для description).
 *
 * targetRef — ссылка на DatasetRow.setupTasks[].ref, не на реальный UUID:
 * ExperimentRunner подставляет настоящий идентификатор задачи, заведённой
 * перед обращением, в момент сверки.
 */
public record ExpectedAction(
        String type,
        @JsonProperty("title_contains") String titleContains,
        @JsonProperty("target_ref") String targetRef,
        @JsonProperty("deadline_offset_days") Integer deadlineOffsetDays,
        @JsonProperty("deadline_hour") Integer deadlineHour,
        String priority,
        String group
) {}
