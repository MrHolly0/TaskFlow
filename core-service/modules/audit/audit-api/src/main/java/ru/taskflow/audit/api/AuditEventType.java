package ru.taskflow.audit.api;

public enum AuditEventType {
    CREATED,
    UPDATED,
    STATUS_CHANGED,
    DELETED,
    FOCUS_HINT_SHOWN,
    STARTED_AFTER_HINT
}
