package ru.taskflow.assistant.api;

public record FocusHintGeneration(
        String hint,
        int inputTokens,
        int outputTokens
) {}
