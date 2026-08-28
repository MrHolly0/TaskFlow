package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.exception.ProposalNotFoundException;
import ru.taskflow.assistant.api.exception.ProposalNotPendingException;
import ru.taskflow.assistant.infrastructure.persistence.ProposalActionJpaEntity;
import ru.taskflow.assistant.infrastructure.persistence.ProposalFactory;
import ru.taskflow.assistant.infrastructure.persistence.ProposalJpaEntity;
import ru.taskflow.assistant.infrastructure.persistence.ProposalMapper;
import ru.taskflow.assistant.infrastructure.persistence.ProposalRepository;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.user.api.UserService;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ведущий принцип части 2в: сказанное пользователем не теряется никогда — но это
 * относится только к настоящему сбою обращения (сеть, тайм-аут, нулевой расход
 * токенов), не к осознанному отказу модели действовать. Обращение не состоялось —
 * реплика превращается в отдельную задачу через createQuick, degrade() переносит в
 * Proposal нулевые токены и задержки (обращения не было — например, речь не
 * распозналась до запуска AgentLoop). Модель ответила, но не предложила ничего —
 * это измеренный результат, а не сбой: silentModelResponse() не создаёт задачу и
 * не пишет её в аудит, но переносит в Proposal реальный расход из AgentOutcome.
 * Иначе измерение путает отказ инфраструктуры с осознанным отказом модели
 * действовать («спасибо» становится задачей «спасибо»).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantServiceImpl implements AssistantService {

    private static final int MAX_TITLE_LENGTH = 512;
    private static final String DEGRADATION_EXPLANATION =
            "Не удалось разобрать сообщение — сохранил его как отдельную задачу целиком.";
    private static final String SILENT_MODEL_EXPLANATION =
            "Не нашёл, что предложить по этой реплике.";
    private static final String VOICE_TRANSCRIPTION_FAILED_TEXT =
            "Голосовое сообщение (не удалось распознать речь)";

    private final AgentLoop agentLoop;
    private final NlpGatewayService nlpGatewayService;
    private final ProposalFactory proposalFactory;
    private final ProposalMapper proposalMapper;
    private final ProposalRepository proposalRepository;
    private final ProposalApplier proposalApplier;
    private final UserService userService;
    private final TaskService taskService;
    private final AuditService auditService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    @Override
    public Proposal handleText(UUID userId, String text, AssistantChannel channel, AssistantEntryPoint entryPoint) {
        ZoneId zone = userService.getTimezone(userId);
        AgentOutcome outcome = agentLoop.run(userId, text, zone, entryPoint);
        return toProposal(userId, text, channel, "TEXT", outcome, degradedSourceFor(channel));
    }

    @Override
    public Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel, AssistantEntryPoint entryPoint) {
        String text = nlpGatewayService.transcribe(audio);
        if (isBlank(text)) {
            return degrade(userId, VOICE_TRANSCRIPTION_FAILED_TEXT, TaskSource.ASSISTANT_BOT_VOICE_DEGRADED, null);
        }

        ZoneId zone = userService.getTimezone(userId);
        AgentOutcome outcome = agentLoop.run(userId, text, zone, entryPoint);
        return toProposal(userId, text, channel, "VOICE", outcome, TaskSource.ASSISTANT_BOT_VOICE_DEGRADED);
    }

    private Proposal toProposal(UUID userId, String text, AssistantChannel channel, String inputKind,
                                 AgentOutcome outcome, TaskSource degradedSource) {
        if (outcome.llmFailed()) {
            return degrade(userId, text, degradedSource, outcome);
        }
        if (outcome.isDeclined()) {
            return declinedResponse(userId, text, outcome);
        }
        if (isEmpty(outcome)) {
            return silentModelResponse(userId, text, outcome);
        }

        ProposalJpaEntity entity = proposalFactory.from(userId, text, channel, inputKind, outcome);
        ProposalJpaEntity saved = proposalRepository.save(entity);
        return withStageLatencies(proposalMapper.toDto(saved), outcome);
    }

    /**
     * Задержки по проходам модели не персистятся (в сущности нет для них
     * столбцов — только общий latencyMs, см. ProposalMapper), поэтому их
     * негде взять при повторном обращении к сохранённому предложению. Здесь,
     * сразу после AgentLoop.run(), они ещё живы в outcome — накладываем их
     * поверх результата маппинга один раз, для ответа на этот же вызов.
     */
    private Proposal withStageLatencies(Proposal dto, AgentOutcome outcome) {
        return new Proposal(dto.id(), dto.shortCode(), dto.userId(), dto.status(), dto.sourceText(),
                dto.clarification(), dto.actions(), dto.createdAt(), dto.expiresAt(), dto.exclusive(),
                dto.ambiguityReason(), dto.rejections(), dto.inputTokens(), dto.outputTokens(),
                dto.totalLatencyMs(), outcome.firstPassLatencyMs(), outcome.secondPassLatencyMs(), dto.modelPasses());
    }

    @Override
    @Transactional(readOnly = true)
    public Proposal findById(UUID userId, UUID proposalId) {
        return proposalRepository.findByIdAndUserId(proposalId, userId)
                .map(proposalMapper::toDto)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));
    }

    @Override
    @Transactional(readOnly = true)
    public Proposal findByShortCode(UUID userId, String shortCode) {
        return proposalRepository.findByShortCodeAndUserId(shortCode, userId)
                .map(proposalMapper::toDto)
                .orElseThrow(() -> new ProposalNotFoundException(null));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Proposal> findLatestPending(UUID userId) {
        return proposalRepository.findLatestPending(userId, OffsetDateTime.now(clock), PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(proposalMapper::toDto);
    }

    @Override
    @Transactional
    public Proposal setActionAccepted(UUID userId, UUID proposalId, int ordinal, boolean accepted) {
        ProposalJpaEntity entity = proposalRepository.findWithActions(proposalId, userId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));

        requirePending(entity, proposalId);

        boolean matched = entity.getActions().stream()
                .filter(action -> action.getOrdinal() == ordinal)
                .findFirst()
                .map(action -> { action.setAccepted(accepted); return true; })
                .orElse(false);
        if (!matched) {
            log.debug("Предложение {}: действие с ordinal={} не найдено, ничего не изменено", proposalId, ordinal);
        }

        ProposalJpaEntity saved = proposalRepository.save(entity);
        return proposalMapper.toDto(saved);
    }

    @Override
    @Transactional
    public Proposal updateActionReminder(UUID userId, UUID proposalId, int ordinal, OffsetDateTime reminderAt) {
        ProposalJpaEntity entity = proposalRepository.findWithActions(proposalId, userId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));

        requirePending(entity, proposalId);

        ProposalActionJpaEntity action = entity.getActions().stream()
                .filter(a -> a.getOrdinal() == ordinal)
                .findFirst()
                .orElseThrow(() -> new ValidationException("действие с ordinal=" + ordinal + " не найдено"));

        AssistantActionType type = AssistantActionType.valueOf(action.getType());
        if (type != AssistantActionType.CREATE && type != AssistantActionType.REMIND) {
            throw new ValidationException("напоминание неприменимо к действию типа " + type);
        }
        if (type == AssistantActionType.REMIND && reminderAt == null) {
            throw new ValidationException("у remind напоминание обязательно");
        }
        if (reminderAt != null && reminderAt.isBefore(OffsetDateTime.now(clock))) {
            throw new ValidationException("время напоминания уже прошло");
        }

        action.setPayload(withReminderAt(action.getPayload(), reminderAt));

        ProposalJpaEntity saved = proposalRepository.save(entity);
        return proposalMapper.toDto(saved);
    }

    /**
     * no_reminder_needed здесь — тот же явный сигнал «решил, что не нужно»,
     * что и у модели (Г1): снятие напоминания в карточке подтверждения —
     * тоже осознанный выбор, не молчание.
     */
    private String withReminderAt(String payloadJson, OffsetDateTime reminderAt) {
        Map<String, Object> payload = new LinkedHashMap<>(readPayload(payloadJson));
        if (reminderAt == null) {
            payload.remove("reminder_at");
            payload.put("no_reminder_needed", true);
        } else {
            payload.put("reminder_at", reminderAt.toString());
            payload.remove("no_reminder_needed");
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new ValidationException("не удалось изменить напоминание");
        }
    }

    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ValidationException("не удалось прочитать действие предложения");
        }
    }

    @Override
    @Transactional
    public Proposal selectAlternative(UUID userId, UUID proposalId, int ordinal) {
        ProposalJpaEntity entity = proposalRepository.findWithActions(proposalId, userId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));

        requirePending(entity, proposalId);

        entity.getActions().forEach(action -> action.setAccepted(action.getOrdinal() == ordinal));

        ProposalJpaEntity saved = proposalRepository.save(entity);
        return proposalMapper.toDto(saved);
    }

    /**
     * Без @Transactional намеренно, как и ProposalApplier.apply — если обернуть вызов
     * proposalApplier.apply в транзакцию, первое же исключение из TaskService пометит
     * её rollback-only и итоговое сохранение упадёт с UnexpectedRollbackException вместо
     * честного частичного результата. См. обоснование в ProposalApplier.
     */
    @Override
    public ApplyResult apply(UUID userId, UUID proposalId) {
        ProposalJpaEntity entity = proposalRepository.findWithActions(proposalId, userId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));

        requirePending(entity, proposalId);

        if (entity.getExpiresAt().isBefore(OffsetDateTime.now(clock))) {
            entity.setStatus(ProposalStatus.EXPIRED.name());
            entity.setResolvedAt(OffsetDateTime.now(clock));
            proposalRepository.save(entity);
            return new ApplyResult(ProposalStatus.EXPIRED, 0, acceptedCount(entity), List.of());
        }

        ApplyResult result = proposalApplier.apply(userId, entity);
        proposalRepository.save(entity);
        return result;
    }

    @Override
    @Transactional
    public void reject(UUID userId, UUID proposalId) {
        ProposalJpaEntity entity = proposalRepository.findByIdAndUserId(proposalId, userId)
                .orElseThrow(() -> new ProposalNotFoundException(proposalId));

        entity.setStatus(ProposalStatus.REJECTED.name());
        entity.setResolvedAt(OffsetDateTime.now(clock));
        proposalRepository.save(entity);
    }

    @Override
    @Transactional
    public int transferOwnership(UUID from, UUID to) {
        return proposalRepository.reassignOwner(from, to);
    }

    private void requirePending(ProposalJpaEntity entity, UUID proposalId) {
        ProposalStatus status = ProposalStatus.valueOf(entity.getStatus());
        if (status != ProposalStatus.PENDING) {
            throw new ProposalNotPendingException(proposalId, status);
        }
    }

    private int acceptedCount(ProposalJpaEntity entity) {
        return (int) entity.getActions().stream().filter(ProposalActionJpaEntity::isAccepted).count();
    }

    private boolean isEmpty(AgentOutcome outcome) {
        return outcome.actions().isEmpty() && isBlank(outcome.clarification()) && isBlank(outcome.assistantText());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private TaskSource degradedSourceFor(AssistantChannel channel) {
        return channel == AssistantChannel.WEB ? TaskSource.ASSISTANT_WEB_DEGRADED : TaskSource.ASSISTANT_BOT_TEXT_DEGRADED;
    }

    /**
     * Только настоящий сбой обращения: outcome — null, если обращения к модели
     * не было вовсе (например, распознавание речи упало до запуска AgentLoop),
     * иначе — llmFailed (сеть, тайм-аут). В обоих случаях расход честно
     * нулевой. Случай «модель ответила, но не предложила ничего» сюда больше
     * не попадает — см. silentModelResponse().
     */
    private Proposal degrade(UUID userId, String text, TaskSource source, AgentOutcome outcome) {
        String title = text.length() > MAX_TITLE_LENGTH ? text.substring(0, MAX_TITLE_LENGTH) : text;
        CreateTaskRequest request = new CreateTaskRequest(title, null, null, null, null, null, null, null, source);
        TaskResponse created = taskService.createQuick(userId, request);
        recordDegradedCreation(userId, created.id());

        OffsetDateTime now = OffsetDateTime.now(clock);
        int inputTokens = outcome != null ? outcome.inputTokens() : 0;
        int outputTokens = outcome != null ? outcome.outputTokens() : 0;
        long totalLatencyMs = outcome != null ? outcome.totalLatencyMs() : 0;
        long firstPassLatencyMs = outcome != null ? outcome.firstPassLatencyMs() : 0;
        long secondPassLatencyMs = outcome != null ? outcome.secondPassLatencyMs() : 0;
        int modelPasses = outcome != null ? outcome.passes() : 0;
        return new Proposal(null, null, userId, ProposalStatus.FAILED, text, DEGRADATION_EXPLANATION, List.of(),
                now, now, false, null, List.of(), inputTokens, outputTokens, totalLatencyMs, firstPassLatencyMs,
                secondPassLatencyMs, modelPasses);
    }

    /**
     * Модель ответила (расход токенов реальный, llmFailed=false), но не
     * предложила ни действий, ни уточнения, ни текста — «спасибо», «привет»
     * и подобное. Это измеренный результат работы модели, а не сбой:
     * задачу из реплики не создаём и recordDegradedCreation не вызываем, в
     * отличие от настоящего сбоя обращения (см. degrade()). До появления у
     * модели явного способа отказаться (следующий заход) пустой ответ —
     * единственный сигнал этого исхода.
     */
    private Proposal silentModelResponse(UUID userId, String text, AgentOutcome outcome) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new Proposal(null, null, userId, ProposalStatus.FAILED, text, SILENT_MODEL_EXPLANATION, List.of(),
                now, now, false, null, List.of(), outcome.inputTokens(), outcome.outputTokens(),
                outcome.totalLatencyMs(), outcome.firstPassLatencyMs(), outcome.secondPassLatencyMs(),
                outcome.passes());
    }

    /**
     * Модель явно отказалась предлагать действие (no_action) — вопрос о
     * данных, реплика без содержания или формулировка, из которой не
     * восстанавливается ни одна команда (Б1; отдельно от двоякости —
     * см. правило 5/6 в AssistantPromptBuilder). Третье состояние, не
     * разновидность сбоя (Б2): задачу не создаём, recordDegradedCreation
     * не вызываем (Б3) — пользователь видит ответ модели вместо пустого
     * предложения.
     */
    private Proposal declinedResponse(UUID userId, String text, AgentOutcome outcome) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new Proposal(null, null, userId, ProposalStatus.DECLINED, text, outcome.assistantText(), List.of(),
                now, now, false, null, List.of(), outcome.inputTokens(), outcome.outputTokens(),
                outcome.totalLatencyMs(), outcome.firstPassLatencyMs(), outcome.secondPassLatencyMs(),
                outcome.passes(), outcome.declineReason());
    }

    private void recordDegradedCreation(UUID userId, UUID taskId) {
        try {
            auditService.record(userId, taskId, AuditEventType.CREATED, Map.of());
        } catch (Exception e) {
            log.warn("Задача из нераспознанной реплики создана, но не записалась в аудит: {}", e.getMessage());
        }
    }
}
