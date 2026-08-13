package ru.taskflow.assistant.infrastructure.web.dto;

import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;

/**
 * applied == null означает, что предложение не подошло под правило
 * автоприменения и ждёт подтверждения — клиент различает два исхода по этому полю.
 */
public record QuickResult(Proposal proposal, ApplyResult applied) {
}
