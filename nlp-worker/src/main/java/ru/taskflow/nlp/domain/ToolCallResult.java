package ru.taskflow.nlp.domain;

import java.util.List;

public record ToolCallResult(List<RawToolCall> toolCalls, String text, int inputTokens, int outputTokens) {

    public static ToolCallResult empty() {
        return new ToolCallResult(List.of(), null, 0, 0);
    }
}
