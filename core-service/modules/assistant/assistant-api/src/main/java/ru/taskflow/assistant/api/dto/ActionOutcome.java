package ru.taskflow.assistant.api.dto;

public record ActionOutcome(
        int ordinal,
        String summary,
        boolean success,
        String error
) {}
