package ru.taskflow.assistant.api;

public enum ProposalStatus {
    PENDING, APPLIED, PARTIALLY_APPLIED, REJECTED, EXPIRED, FAILED,
    /** Модель осознанно отказалась предлагать действие (no_action) — не сбой, третье состояние. */
    DECLINED
}
