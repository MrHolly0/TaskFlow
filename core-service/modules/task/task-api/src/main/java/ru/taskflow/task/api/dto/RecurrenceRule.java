package ru.taskflow.task.api.dto;

import ru.taskflow.task.api.RecurrenceType;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Правило повтора задачи. Смысл полей зависит от type:
 * DAILY/WEEKLY — каждый intervalN-й день/неделю (по умолчанию 1); WEEKLY
 * дополнительно может назвать daysOfWeek — тогда повтор идёт по этим дням
 * недели, а не по интервалу от даты закрытия; WEEKDAYS — по будням, прочие
 * поля не участвуют; MONTHLY — dayOfMonth обязателен. endsAt пусто — значит
 * бессрочно. CUSTOM в этом поле не встречается — отклоняется на входе.
 */
public record RecurrenceRule(
        RecurrenceType type,
        Integer intervalN,
        List<DayOfWeek> daysOfWeek,
        Integer dayOfMonth,
        OffsetDateTime endsAt
) {}
