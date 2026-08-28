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
        @JsonProperty("reminder_minutes_before_target_deadline") Integer reminderMinutesBeforeTargetDeadline,
        // Блок Г ("напоминание в предложении"): точное время напоминания теперь
        // подбирает модель сама, значит ожидание — диапазон, а не число. Оба
        // поля диапазона заданы или оба пусты; пустой конец — открытая граница
        // (например, только Min — "не раньше", без верхней границы).
        @JsonProperty("reminder_minutes_before_target_deadline_min") Integer reminderMinutesBeforeTargetDeadlineMin,
        @JsonProperty("reminder_minutes_before_target_deadline_max") Integer reminderMinutesBeforeTargetDeadlineMax,
        // Для напоминаний без срока-цели (совет вида "рабочие часы") — диапазон
        // часа локального времени напоминания, та же логика открытых границ.
        @JsonProperty("reminder_hour_min") Integer reminderHourMin,
        @JsonProperty("reminder_hour_max") Integer reminderHourMax,
        // true — по датасету напоминание не нужно вовсе: сверяется, что
        // reminder_at не заполнен И no_reminder_needed=true у самого действия.
        // Так "решила не предлагать" отличимо от "забыла предложить" не только
        // в проде, но и в измерении.
        @JsonProperty("expect_no_reminder") Boolean expectNoReminder
) {
    // Совместимость со старыми вызовами: до проверки времени напоминания (блок В) этих полей не было.
    public ExpectedAction(String type, String titleContains, String targetRef, Integer deadlineOffsetDays,
                           Integer deadlineHour, String priority, String group) {
        this(type, titleContains, targetRef, deadlineOffsetDays, deadlineHour, priority, group, null, null, null);
    }

    // Совместимость: до диапазонов и expect_no_reminder (блок Г) было только точное время.
    public ExpectedAction(String type, String titleContains, String targetRef, Integer deadlineOffsetDays,
                           Integer deadlineHour, String priority, String group, Integer reminderOffsetDays,
                           Integer reminderHour, Integer reminderMinutesBeforeTargetDeadline) {
        this(type, titleContains, targetRef, deadlineOffsetDays, deadlineHour, priority, group, reminderOffsetDays,
                reminderHour, reminderMinutesBeforeTargetDeadline, null, null, null, null, null);
    }
}
