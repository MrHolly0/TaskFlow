package ru.taskflow.assistant.api;

public enum AssistantActionType {
    CREATE, COMPLETE, RESCHEDULE, UPDATE, CANCEL,
    /** Напоминание на существующую задачу — не меняет срок задачи. */
    REMIND
}
