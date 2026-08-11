package ru.taskflow.assistant.application;

import java.util.Map;
import java.util.UUID;

public record TaskContextWindow(String rendered, Map<String, UUID> refs, Map<String, String> titles) {

    public UUID resolve(String ref) {
        return ref == null ? null : refs.get(ref);
    }

    public String title(String ref) {
        return ref == null ? null : titles.get(ref);
    }

    public int size() {
        return refs.size();
    }
}
