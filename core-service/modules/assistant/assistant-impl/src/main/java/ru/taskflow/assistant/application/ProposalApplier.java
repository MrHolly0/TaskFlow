package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ActionOutcome;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.infrastructure.persistence.ProposalActionJpaEntity;
import ru.taskflow.assistant.infrastructure.persistence.ProposalJpaEntity;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Применяет подтверждённое предложение. Каждое действие идёт через TaskService
 * отдельным вызовом — метод намеренно не транзакционный, см. спеку части 2в:
 * если обернуть его в @Transactional, первое же исключение из TaskService
 * (а его методы транзакционны сами по себе) пометит внешнюю транзакцию
 * rollback-only, и честное "применено 2 из 3" превратится в жёсткий отказ
 * всего запроса при финальном сохранении.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProposalApplier {

    private static final String GENERIC_FAILURE_MESSAGE = "не удалось применить действие";

    private final ActionValidator actionValidator;
    private final TaskService taskService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ApplyResult apply(UUID userId, ProposalJpaEntity proposal) {
        List<ProposalActionJpaEntity> accepted = proposal.getActions().stream()
                .filter(ProposalActionJpaEntity::isAccepted)
                .sorted(Comparator.comparingInt(ProposalActionJpaEntity::getOrdinal))
                .toList();

        List<ActionOutcome> outcomes = new ArrayList<>();
        int appliedCount = 0;

        for (ProposalActionJpaEntity action : accepted) {
            if (applyOne(userId, proposal, action)) {
                appliedCount++;
                outcomes.add(new ActionOutcome(action.getOrdinal(), action.getSummary(), true, null));
            } else {
                outcomes.add(new ActionOutcome(action.getOrdinal(), action.getSummary(), false, action.getApplyError()));
            }
        }

        ProposalStatus status = resolveStatus(appliedCount, accepted.size());
        proposal.setStatus(status.name());
        proposal.setResolvedAt(OffsetDateTime.now());

        return new ApplyResult(status, appliedCount, accepted.size(), outcomes);
    }

    /**
     * Возвращает true, если действие применено успешно. Любая ошибка — валидации,
     * исключение из TaskService при ревалидации или при диспетчеризации — ловится
     * здесь целиком одним try и не идёт дальше по стеку, чтобы не сорвать применение
     * остальных действий предложения. Запись в аудит — намеренно отдельный,
     * "лучше-по-возможности" try внутри: если TaskService уже применил действие,
     * а аудит не записался, действие всё равно должно считаться применённым —
     * откатить TaskService нельзя (он транзакционен сам по себе), и падать на
     * "применено, но не залогировано" означало бы врать пользователю об отказе
     * там, где данные реально изменились.
     */
    private boolean applyOne(UUID userId, ProposalJpaEntity proposal, ProposalActionJpaEntity action) {
        try {
            AssistantActionType type = AssistantActionType.valueOf(action.getType());

            ActionValidator.ValidationResult validation =
                    actionValidator.revalidateForApply(userId, type, action.getTargetTaskId());
            if (!validation.valid()) {
                action.setApplyError(validation.error());
                return false;
            }

            Map<String, Object> payload = readPayload(action.getPayload());
            UUID targetTaskId = validation.targetTaskId();

            UUID appliedTaskId = dispatch(userId, proposal, type, targetTaskId, payload);
            action.setAppliedTaskId(appliedTaskId);
            recordAudit(userId, appliedTaskId, type, payload, action.getOrdinal());
            return true;
        } catch (Exception e) {
            log.warn("Не удалось применить действие {} предложения: {}", action.getOrdinal(), e.getMessage());
            action.setApplyError(GENERIC_FAILURE_MESSAGE);
            return false;
        }
    }

    private void recordAudit(UUID userId, UUID taskId, AssistantActionType type,
                              Map<String, Object> payload, int ordinal) {
        try {
            auditService.record(userId, taskId, eventTypeFor(type), payload);
        } catch (Exception e) {
            log.warn("Действие {} применено, но не записалось в аудит: {}", ordinal, e.getMessage());
        }
    }

    private UUID dispatch(UUID userId, ProposalJpaEntity proposal, AssistantActionType type,
                           UUID targetTaskId, Map<String, Object> payload) {
        return switch (type) {
            case CREATE -> createTask(userId, proposal, payload);
            case COMPLETE -> {
                taskService.complete(userId, targetTaskId);
                yield targetTaskId;
            }
            case RESCHEDULE -> {
                UpdateTaskRequest request = new UpdateTaskRequest(
                        null, null, null, null,
                        parseDeadline(payload.get("new_deadline")), null, null, null);
                taskService.update(userId, targetTaskId, request);
                yield targetTaskId;
            }
            case UPDATE -> {
                // payload["group"] намеренно не читаем: UpdateTaskRequest несёт только
                // groupId (UUID), а модель даёт название группы строкой — разрешение
                // имени в UUID инкапсулировано в TaskServiceImpl.create и недоступно
                // через публичный TaskService. Смена группы через update_task пока
                // молча не применяется; расширение UpdateTaskRequest полем groupName
                // по аналогии с CreateTaskRequest — отдельная задача.
                UpdateTaskRequest request = new UpdateTaskRequest(
                        asString(payload.get("title")),
                        asString(payload.get("description")),
                        parsePriority(payload.get("priority")),
                        null, null, null, null, null);
                taskService.update(userId, targetTaskId, request);
                yield targetTaskId;
            }
            case CANCEL -> {
                UpdateTaskRequest request = new UpdateTaskRequest(
                        null, null, null, TaskStatus.CANCELLED, null, null, null, null);
                taskService.update(userId, targetTaskId, request);
                yield targetTaskId;
            }
        };
    }

    private UUID createTask(UUID userId, ProposalJpaEntity proposal, Map<String, Object> payload) {
        CreateTaskRequest request = new CreateTaskRequest(
                asString(payload.get("title")),
                asString(payload.get("description")),
                parsePriority(payload.get("priority")),
                parseDeadline(payload.get("deadline")),
                null,
                asString(payload.get("group")),
                asTags(payload.get("tags")),
                null,
                sourceOf(proposal)
        );
        TaskResponse created = taskService.createQuick(userId, request);
        return created.id();
    }

    private TaskSource sourceOf(ProposalJpaEntity proposal) {
        if ("WEB".equals(proposal.getSourceChannel())) {
            return TaskSource.WEB;
        }
        return "VOICE".equals(proposal.getInputKind()) ? TaskSource.BOT_VOICE : TaskSource.BOT_TEXT;
    }

    private ProposalStatus resolveStatus(int appliedCount, int totalCount) {
        if (totalCount == 0) {
            return ProposalStatus.APPLIED;
        }
        if (appliedCount == totalCount) {
            return ProposalStatus.APPLIED;
        }
        if (appliedCount == 0) {
            return ProposalStatus.FAILED;
        }
        return ProposalStatus.PARTIALLY_APPLIED;
    }

    /**
     * CREATE -> CREATED и UPDATE -> UPDATED очевидны. COMPLETE и CANCEL меняют
     * TaskStatus, поэтому STATUS_CHANGED. RESCHEDULE формально меняет только
     * дедлайн, а не статус, но выделенного события для срока в AuditEventType
     * нет — STATUS_CHANGED здесь ближе по смыслу к "что-то изменилось в жизненном
     * цикле задачи", чем UPDATED, которое в остальном коде обозначает правку полей
     * содержимого (title/description/priority).
     */
    private AuditEventType eventTypeFor(AssistantActionType type) {
        return switch (type) {
            case CREATE -> AuditEventType.CREATED;
            case COMPLETE, RESCHEDULE, CANCEL -> AuditEventType.STATUS_CHANGED;
            case UPDATE -> AuditEventType.UPDATED;
        };
    }

    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Не удалось разобрать payload действия, вернём пустой: {}", json);
            return Map.of();
        }
    }

    private String asString(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isBlank() ? null : value;
    }

    private List<String> asTags(Object raw) {
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return null;
    }

    private TaskPriority parsePriority(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return TaskPriority.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private OffsetDateTime parseDeadline(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
