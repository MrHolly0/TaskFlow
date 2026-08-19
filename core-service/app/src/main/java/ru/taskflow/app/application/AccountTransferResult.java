package ru.taskflow.app.application;

public record AccountTransferResult(
        int tasks,
        int groups,
        int tags,
        int notifications,
        int auditEvents,
        int proposals,
        int identities
) {
}
