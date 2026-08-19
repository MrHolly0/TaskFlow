package ru.taskflow.nlp.domain;

public interface ToolCallProvider {

    ToolCallResult call(ToolCallRequest req);

    default String name() {
        return getClass().getSimpleName();
    }
}
