package ru.taskflow.assistant.api.dto;

import ru.taskflow.assistant.api.ProposalStatus;

import java.util.List;

public record ApplyResult(
        ProposalStatus status,
        int appliedCount,
        int totalCount,
        List<ActionOutcome> outcomes
) {}
