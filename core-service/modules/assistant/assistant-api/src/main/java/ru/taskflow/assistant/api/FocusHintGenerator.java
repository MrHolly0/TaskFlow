package ru.taskflow.assistant.api;

public interface FocusHintGenerator {
    FocusHintGeneration generate(String title, String description);
}
