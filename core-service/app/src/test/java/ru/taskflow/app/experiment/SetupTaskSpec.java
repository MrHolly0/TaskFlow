package ru.taskflow.app.experiment;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Задача, которую нужно завести перед обращением, чтобы реплика имела к чему
 * относиться (UPDATE_EXISTING, COMPLETE_CANCEL, MIXED_OPERATIONS, AMBIGUOUS).
 * deadlineOffsetDays — со сдвигом от текущей даты на момент прогона, а не
 * абсолютная дата: датасет один и тот же должен работать в любой день.
 */
public record SetupTaskSpec(
        String ref,
        String title,
        String group,
        String priority,
        @JsonProperty("deadline_offset_days") Integer deadlineOffsetDays
) {}
