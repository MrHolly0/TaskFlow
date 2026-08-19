package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.user.api.UserService;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ведущий принцип части 2в: сказанное пользователем не теряется никогда. Отказ LLM
 * и пустой ответ модели деградируют одинаково — реплика превращается в отдельную
 * задачу через createQuick, а не пропадает вместе с несостоявшимся предложением.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantServiceImpl implements AssistantService {

    private static final int MAX_TITLE_LENGTH = 512;
    private static final String DEGRADATION_EXPLANATION =
            "Не удалось разобрать сообщение — сохранил его как отдельную задачу целиком.";
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

    @Override
    public Proposal handleText(UUID userId, String text, AssistantChannel channel, AssistantEntryPoint entryPoint) {
        ZoneId zone = userService.getTimezone(userId);
        AgentOutcome outcome = agentLoop.run(userId, text, zone, entryPoint);
        return toProposal(userId, text, channel, "TEXT", outcome, sourceFor(channel));
    }

    @Override
    public Proposal handleVoice(UUID userId, byte[] audio, AssistantChannel channel, AssistantEntryPoint entryPoint) {
        String text = nlpGatewayService.transcribe(audio);
        if (isBlank(text)) {
            return degrade(userId, VOICE_TRANSCRIPTION_FAILED_TEXT, TaskSource.BOT_VOICE);
        }

        ZoneId zone = userService.getTimezone(userId);
        AgentOutcome outcome = agentLoop.run(userId, text, zone, entryPoint);
        return toProposal(userId, text, channel, "VOICE", outcome, TaskSource.BOT_VOICE);
    }

    private Proposal toProposal(UUID userId, String text, AssistantChannel channel, String inputKind,
                                 AgentOutcome outcome, TaskSource degradedSource) {
        if (outcome.llmFailed() || isEmpty(outcome)) {
            return degrade(userId, text, degradedSource);
        }

        ProposalJpaEntity entity = proposalFactory.from(userId, text, channel, inputKind, outcome);
        ProposalJpaEntity saved = proposalRepository.save(entity);
        return proposalMapper.toDto(saved);
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

    private TaskSource sourceFor(AssistantChannel channel) {
        return channel == AssistantChannel.WEB ? TaskSource.WEB : TaskSource.BOT_TEXT;
    }

    private Proposal degrade(UUID userId, String text, TaskSource source) {
        String title = text.length() > MAX_TITLE_LENGTH ? text.substring(0, MAX_TITLE_LENGTH) : text;
        CreateTaskRequest request = new CreateTaskRequest(title, null, null, null, null, null, null, null, source);
        TaskResponse created = taskService.createQuick(userId, request);
        recordDegradedCreation(userId, created.id());

        OffsetDateTime now = OffsetDateTime.now(clock);
        return new Proposal(null, null, userId, ProposalStatus.FAILED, text, DEGRADATION_EXPLANATION, List.of(), now, now);
    }

    private void recordDegradedCreation(UUID userId, UUID taskId) {
        try {
            auditService.record(userId, taskId, AuditEventType.CREATED, Map.of());
        } catch (Exception e) {
            log.warn("Задача из нераспознанной реплики создана, но не записалась в аудит: {}", e.getMessage());
        }
    }
}
