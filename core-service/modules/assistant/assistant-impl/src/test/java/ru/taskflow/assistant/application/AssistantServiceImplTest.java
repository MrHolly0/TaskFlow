package ru.taskflow.assistant.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantServiceImplTest {

    @Mock
    private AgentLoop agentLoop;
    @Mock
    private NlpGatewayService nlpGatewayService;
    @Mock
    private ProposalFactory proposalFactory;
    @Mock
    private ProposalMapper proposalMapper;
    @Mock
    private ProposalRepository proposalRepository;
    @Mock
    private ProposalApplier proposalApplier;
    @Mock
    private UserService userService;
    @Mock
    private TaskService taskService;
    @Mock
    private AuditService auditService;

    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-12T10:00:00+03:00");
    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.ofHours(3));
    private final ZoneId zone = ZoneId.of("Europe/Moscow");

    private AssistantServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AssistantServiceImpl(agentLoop, nlpGatewayService, proposalFactory, proposalMapper,
                proposalRepository, proposalApplier, userService, taskService, auditService, clock);
    }

    private AgentOutcome emptyWindowOutcome(List<ru.taskflow.assistant.api.dto.ProposedAction> actions,
                                             String clarification, String assistantText, boolean llmFailed) {
        TaskContextWindow window = new TaskContextWindow("", java.util.Map.of(), java.util.Map.of());
        return new AgentOutcome(actions, List.of(), clarification, List.of(), assistantText, window, 1, llmFailed);
    }

    private ProposalActionJpaEntity action(int ordinal, boolean accepted) {
        ProposalActionJpaEntity action = new ProposalActionJpaEntity();
        action.setOrdinal(ordinal);
        action.setType("COMPLETE");
        action.setSummary("действие " + ordinal);
        action.setAccepted(accepted);
        return action;
    }

    private ProposalJpaEntity proposal(ProposalStatus status, OffsetDateTime expiresAt, ProposalActionJpaEntity... actions) {
        ProposalJpaEntity entity = new ProposalJpaEntity();
        entity.setUserId(userId);
        entity.setStatus(status.name());
        entity.setExpiresAt(expiresAt);
        for (ProposalActionJpaEntity action : actions) {
            entity.addAction(action);
        }
        return entity;
    }

    @Test
    void handleText_savesPendingProposal() {
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(
                List.of(new ru.taskflow.assistant.api.dto.ProposedAction(1, ru.taskflow.assistant.api.AssistantActionType.COMPLETE,
                        UUID.randomUUID(), java.util.Map.of(), "закрыть задачу", true)),
                null, null, false);
        when(agentLoop.run(userId, "закрой молоко", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        ProposalJpaEntity entity = new ProposalJpaEntity();
        when(proposalFactory.from(eq(userId), eq("закрой молоко"), eq(AssistantChannel.TELEGRAM), eq("TEXT"), eq(outcome)))
                .thenReturn(entity);
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Proposal result = service.handleText(userId, "закрой молоко", AssistantChannel.TELEGRAM);

        assertThat(result).isEqualTo(expectedDto);
        verify(taskService, never()).createQuick(any(), any());
    }

    @Test
    void handleText_savesRawTextTaskWhenLlmFailed() {
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(List.of(), null, null, true);
        when(agentLoop.run(userId, "хм", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());

        Proposal result = service.handleText(userId, "хм", AssistantChannel.TELEGRAM);

        ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).createQuick(eq(userId), captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("хм");
        assertThat(captor.getValue().source()).isEqualTo(TaskSource.BOT_TEXT);
        verify(proposalRepository, never()).save(any());
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
        assertThat(result.sourceText()).isEqualTo("хм");
    }

    @Test
    void handleText_savesRawTextTaskWhenOutcomeEmpty() {
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(List.of(), null, null, false);
        when(agentLoop.run(userId, "непонятно что", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());

        Proposal result = service.handleText(userId, "непонятно что", AssistantChannel.TELEGRAM);

        verify(taskService).createQuick(eq(userId), any());
        verify(proposalRepository, never()).save(any());
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
    }

    @Test
    void handleText_truncatesLongTextToColumnLimit() {
        String longText = "а".repeat(600);
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(List.of(), null, null, true);
        when(agentLoop.run(userId, longText, zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());

        service.handleText(userId, longText, AssistantChannel.TELEGRAM);

        ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).createQuick(eq(userId), captor.capture());
        assertThat(captor.getValue().title()).hasSize(512);
        assertThat(captor.getValue().title()).isEqualTo(longText.substring(0, 512));
    }

    @Test
    void findById_rejectsForeignUser() {
        UUID proposalId = UUID.randomUUID();
        when(proposalRepository.findByIdAndUserId(proposalId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(userId, proposalId))
                .isInstanceOf(ProposalNotFoundException.class);
    }

    @Test
    void findLatestPending_returnsMappedProposal() {
        ProposalJpaEntity entity = new ProposalJpaEntity();
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "текст", null, List.of(), now, now.plusHours(24));
        when(proposalRepository.findLatestPending(eq(userId), eq(now), any()))
                .thenReturn(List.of(entity));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Optional<Proposal> result = service.findLatestPending(userId);

        assertThat(result).contains(expectedDto);
    }

    @Test
    void findLatestPending_returnsEmptyWhenNone() {
        when(proposalRepository.findLatestPending(eq(userId), eq(now), any()))
                .thenReturn(List.of());

        Optional<Proposal> result = service.findLatestPending(userId);

        assertThat(result).isEmpty();
    }

    @Test
    void selectAlternative_acceptsChosenAndRejectsOthers() {
        UUID proposalId = UUID.randomUUID();
        ProposalActionJpaEntity first = action(1, true);
        ProposalActionJpaEntity second = action(2, false);
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), first, second);
        when(proposalRepository.findWithActions(proposalId, userId)).thenReturn(Optional.of(entity));
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "текст", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Proposal result = service.selectAlternative(userId, proposalId, 2);

        assertThat(result).isEqualTo(expectedDto);
        assertThat(first.isAccepted()).isFalse();
        assertThat(second.isAccepted()).isTrue();
    }

    @Test
    void selectAlternative_rejectsNonPending() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), action(1, true));
        when(proposalRepository.findWithActions(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.selectAlternative(userId, proposalId, 1))
                .isInstanceOf(ProposalNotPendingException.class);
    }

    @Test
    void setActionAccepted_rejectsNonPending() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), action(1, true));
        when(proposalRepository.findWithActions(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.setActionAccepted(userId, proposalId, 1, false))
                .isInstanceOf(ProposalNotPendingException.class);
    }

    @Test
    void apply_rejectsExpiredProposal() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.minusHours(1), action(1, true));
        when(proposalRepository.findWithActions(proposalId, userId)).thenReturn(Optional.of(entity));

        ApplyResult result = service.apply(userId, proposalId);

        assertThat(result.status()).isEqualTo(ProposalStatus.EXPIRED);
        assertThat(result.appliedCount()).isEqualTo(0);
        assertThat(entity.getStatus()).isEqualTo(ProposalStatus.EXPIRED.name());
        verify(proposalApplier, never()).apply(any(), any());
        verify(proposalRepository).save(entity);
    }

    @Test
    void apply_rejectsAlreadyApplied() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.APPLIED, now.plusHours(24), action(1, true));
        when(proposalRepository.findWithActions(proposalId, userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.apply(userId, proposalId))
                .isInstanceOf(ProposalNotPendingException.class);
        verify(proposalApplier, never()).apply(any(), any());
    }

    @Test
    void reject_setsRejectedStatus() {
        UUID proposalId = UUID.randomUUID();
        ProposalJpaEntity entity = proposal(ProposalStatus.PENDING, now.plusHours(24), action(1, true));
        when(proposalRepository.findByIdAndUserId(proposalId, userId)).thenReturn(Optional.of(entity));

        service.reject(userId, proposalId);

        assertThat(entity.getStatus()).isEqualTo(ProposalStatus.REJECTED.name());
        assertThat(entity.getResolvedAt()).isEqualTo(now);
        verify(proposalRepository).save(entity);
    }

    @Test
    void handleVoice_runsLoopOnTranscribedText() {
        byte[] audio = {1, 2, 3};
        when(nlpGatewayService.transcribe(audio)).thenReturn("закрой молоко");
        when(userService.getTimezone(userId)).thenReturn(zone);
        AgentOutcome outcome = emptyWindowOutcome(
                List.of(new ru.taskflow.assistant.api.dto.ProposedAction(1, ru.taskflow.assistant.api.AssistantActionType.COMPLETE,
                        UUID.randomUUID(), java.util.Map.of(), "закрыть задачу", true)),
                null, null, false);
        when(agentLoop.run(userId, "закрой молоко", zone, AssistantEntryPoint.CHAT)).thenReturn(outcome);

        ProposalJpaEntity entity = new ProposalJpaEntity();
        when(proposalFactory.from(eq(userId), eq("закрой молоко"), eq(AssistantChannel.TELEGRAM), eq("VOICE"), eq(outcome)))
                .thenReturn(entity);
        when(proposalRepository.save(entity)).thenReturn(entity);
        Proposal expectedDto = new Proposal(UUID.randomUUID(), "CODE1234", userId, ProposalStatus.PENDING,
                "закрой молоко", null, List.of(), now, now.plusHours(24));
        when(proposalMapper.toDto(entity)).thenReturn(expectedDto);

        Proposal result = service.handleVoice(userId, audio, AssistantChannel.TELEGRAM);

        assertThat(result).isEqualTo(expectedDto);
        verify(taskService, never()).createQuick(any(), any());
    }

    @Test
    void handleVoice_savesRawTaskWhenTranscriptionFails() {
        byte[] audio = {1, 2, 3};
        when(nlpGatewayService.transcribe(audio)).thenReturn(null);
        when(taskService.createQuick(eq(userId), any())).thenReturn(taskResponse());

        Proposal result = service.handleVoice(userId, audio, AssistantChannel.TELEGRAM);

        ArgumentCaptor<CreateTaskRequest> captor = ArgumentCaptor.forClass(CreateTaskRequest.class);
        verify(taskService).createQuick(eq(userId), captor.capture());
        assertThat(captor.getValue().source()).isEqualTo(TaskSource.BOT_VOICE);
        verify(agentLoop, never()).run(any(), any(), any(), any());
        verify(proposalRepository, never()).save(any());
        assertThat(result.status()).isEqualTo(ProposalStatus.FAILED);
    }

    @Test
    void transferOwnership_delegatesToRepository() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(proposalRepository.reassignOwner(from, to)).thenReturn(3);

        int result = service.transferOwnership(from, to);

        assertThat(result).isEqualTo(3);
        verify(proposalRepository).reassignOwner(from, to);
    }

    private TaskResponse taskResponse() {
        return new TaskResponse(UUID.randomUUID(), "х", null, null, null, null, null, null, null, null,
                List.of(), now, now, null);
    }
}
