package ru.taskflow.task.application;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Одна строка размеченного набора docs/vkr/focus/hours-gate-labels.json. */
record HoursGateLabelRow(
        String title,
        boolean junk,
        @JsonProperty("tied_to_hours") Boolean tiedToHours,
        boolean active,
        String note
) {}
