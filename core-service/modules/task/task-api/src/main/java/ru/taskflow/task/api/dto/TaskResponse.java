package ru.taskflow.task.api.dto;

import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        String title,
        String description,
        TaskPriority priority,
        TaskStatus status,
        OffsetDateTime deadline,
        OffsetDateTime plannedDate,
        Integer estimateMinutes,
        TaskSource source,
        UUID groupId,
        String groupName,
        List<String> tags,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt,
        List<ReminderResponse> reminders,
        OffsetDateTime startedAt,
        OffsetDateTime plannedDateSetAt,
        RecurrenceRule recurrence,
        // Б1: настойчивость по задаче — ставит человек, см. TaskJpaEntity.persistentReminder.
        boolean persistentReminder
) {
    public TaskResponse(UUID id, String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, Integer estimateMinutes, TaskSource source, UUID groupId, String groupName,
            List<String> tags, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime completedAt,
            List<ReminderResponse> reminders) {
        this(id, title, description, priority, status, deadline, null, estimateMinutes, source, groupId, groupName,
                tags, createdAt, updatedAt, completedAt, reminders, null, null, null, false);
    }

    public TaskResponse(UUID id, String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, OffsetDateTime plannedDate, Integer estimateMinutes, TaskSource source,
            UUID groupId, String groupName, List<String> tags, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime completedAt, List<ReminderResponse> reminders) {
        this(id, title, description, priority, status, deadline, plannedDate, estimateMinutes, source, groupId,
                groupName, tags, createdAt, updatedAt, completedAt, reminders, null, null, null, false);
    }

    public TaskResponse(UUID id, String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, OffsetDateTime plannedDate, Integer estimateMinutes, TaskSource source,
            UUID groupId, String groupName, List<String> tags, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime completedAt, List<ReminderResponse> reminders, OffsetDateTime startedAt,
            OffsetDateTime plannedDateSetAt) {
        this(id, title, description, priority, status, deadline, plannedDate, estimateMinutes, source, groupId,
                groupName, tags, createdAt, updatedAt, completedAt, reminders, startedAt, plannedDateSetAt, null,
                false);
    }

    // Совместимость: до Б1 (настойчивость) канонический вид заканчивался на recurrence.
    public TaskResponse(UUID id, String title, String description, TaskPriority priority, TaskStatus status,
            OffsetDateTime deadline, OffsetDateTime plannedDate, Integer estimateMinutes, TaskSource source,
            UUID groupId, String groupName, List<String> tags, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime completedAt, List<ReminderResponse> reminders, OffsetDateTime startedAt,
            OffsetDateTime plannedDateSetAt, RecurrenceRule recurrence) {
        this(id, title, description, priority, status, deadline, plannedDate, estimateMinutes, source, groupId,
                groupName, tags, createdAt, updatedAt, completedAt, reminders, startedAt, plannedDateSetAt,
                recurrence, false);
    }
}
