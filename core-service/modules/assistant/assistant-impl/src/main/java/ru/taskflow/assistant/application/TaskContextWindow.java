package ru.taskflow.assistant.application;

import java.util.Map;
import java.util.UUID;

public record TaskContextWindow(String rendered, Map<String, UUID> refs) {

    public UUID resolve(String ref) {
        return ref == null ? null : refs.get(ref);
    }

    public int size() {
        return refs.size();
    }
}
