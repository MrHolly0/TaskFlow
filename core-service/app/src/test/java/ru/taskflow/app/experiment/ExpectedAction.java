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
        String group,
        // Блок В: время напоминания (reminder_at) — либо абсолютное (offset+hour от
        // дня прогона, та же схема, что и у deadline выше), либо отступ от срока
        // задачи-цели в минутах. Ровно один из двух способов имеет смысл на строку:
        // абсолютное время — для remind без setup-дедлайна и для create с
        // reminder_at; отступ от дедлайна — для remind на задачу с уже известным
        // сроком (сверяется с её реальным, а не продекларированным дедлайном —
        // см. ActionMatcher.match(..., setupRefToDeadline, ...)).
        @JsonProperty("reminder_offset_days") Integer reminderOffsetDays,
        @JsonProperty("reminder_hour") Integer reminderHour,
        @JsonProperty("reminder_minutes_before_target_deadline") Integer reminderMinutesBeforeTargetDeadline
) {
    // Совместимость со старыми вызовами: до проверки времени напоминания (блок В) этих полей не было.
    public ExpectedAction(String type, String titleContains, String targetRef, Integer deadlineOffsetDays,
                           Integer deadlineHour, String priority, String group) {
        this(type, titleContains, targetRef, deadlineOffsetDays, deadlineHour, priority, group, null, null, null);
    }
}
