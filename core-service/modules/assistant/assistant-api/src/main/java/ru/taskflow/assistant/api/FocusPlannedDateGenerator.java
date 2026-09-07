package ru.taskflow.assistant.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

public interface FocusPlannedDateGenerator {
    PlannedDateGeneration generate(UUID taskId, String title, String description, LocalDate today, ZoneId zone);
}
