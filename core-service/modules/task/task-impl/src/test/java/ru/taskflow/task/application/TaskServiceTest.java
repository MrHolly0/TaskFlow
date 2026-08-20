package ru.taskflow.task.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.task.api.exception.TaskNotFoundException;
import ru.taskflow.task.infrastructure.persistence.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;
    @Mock
    private GroupStyleResolver groupStyleResolver;

    @InjectMocks
    private TaskServiceImpl taskService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @Test
    void create_savesAndReturnsResponse() {
        var request = new CreateTaskRequest("купить молоко", null, null, null, null, null, List.of(), null, null);
        var entity = new TaskJpaEntity();
        entity.setUserId(userId);
        entity.setTitle("купить молоко");
        var response = mockResponse(taskId, "купить молоко");

        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.create(userId, request);

        assertThat(result.title()).isEqualTo("купить молоко");
        verify(taskRepository).save(any(TaskJpaEntity.class));
    }

    @Test
    void create_withDeadline_schedulesReminder() {
        var deadline = OffsetDateTime.parse("2026-08-25T10:00:00Z");
        var request = new CreateTaskRequest("сдать курсовую", null, null, deadline, null, null, List.of(), null, null);
        var entity = new TaskJpaEntity();
        entity.setId(taskId);
        entity.setUserId(userId);
        entity.setTitle("сдать курсовую");
        entity.setDeadline(deadline);
        var response = mockResponse(taskId, "сдать курсовую");

        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.create(userId, request);

        verify(notificationService).scheduleTaskReminder(userId, taskId, "сдать курсовую", deadline);
    }

    @Test
    void create_withoutDeadline_doesNotScheduleReminder() {
        var request = new CreateTaskRequest("купить молоко", null, null, null, null, null, List.of(), null, null);
        var entity = new TaskJpaEntity();
        entity.setId(taskId);
        entity.setUserId(userId);
        entity.setTitle("купить молоко");
        var response = mockResponse(taskId, "купить молоко");

        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.create(userId, request);

        verify(notificationService, never()).scheduleTaskReminder(any(), any(), any(), any());
    }

    @Test
    void findById_returnsTask_whenExists() {
        var entity = taskEntity();
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.findById(userId, taskId);

        assertThat(result.id()).isEqualTo(taskId);
    }

    @Test
    void findById_throwsNotFound_whenMissing() {
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.findById(userId, taskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void update_changesTitle() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest("новый заголовок", null, null, null, null, null, null, null, null);
        var response = mockResponse(taskId, "новый заголовок");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.update(userId, taskId, request);

        assertThat(entity.getTitle()).isEqualTo("новый заголовок");
        assertThat(result.title()).isEqualTo("новый заголовок");
    }

    @Test
    void update_recordsChangedFieldsInAudit() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest("новое название", null, null, null, null, null, null, null, null);
        var response = mockResponse(taskId, "новое название");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<Map> deltaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(userId), eq(taskId), eq(AuditEventType.UPDATED), deltaCaptor.capture());
        assertThat(deltaCaptor.getValue()).containsEntry("title", "новое название");
    }

    @Test
    void update_changesGroupToExisting() {
        var entity = taskEntity();
        var existingGroup = new GroupJpaEntity();
        existingGroup.setId(UUID.randomUUID());
        existingGroup.setUserId(userId);
        existingGroup.setName("Работа");
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Работа", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Работа")).thenReturn(Optional.of(existingGroup));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        assertThat(entity.getGroup()).isEqualTo(existingGroup);
        verify(groupRepository, never()).save(any());
    }

    @Test
    void update_createsGroupWhenMissing() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Новая группа", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Новая группа")).thenReturn(Optional.empty());
        when(groupStyleResolver.resolve("Новая группа")).thenReturn(new GroupStyleResolver.GroupStyle("blue", "folder"));
        when(groupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<GroupJpaEntity> groupCaptor = ArgumentCaptor.forClass(GroupJpaEntity.class);
        verify(groupRepository).save(groupCaptor.capture());
        assertThat(groupCaptor.getValue().getName()).isEqualTo("Новая группа");
        assertThat(entity.getGroup()).isEqualTo(groupCaptor.getValue());
    }

    @Test
    void update_setsColorAndIconOnAutoCreatedGroup() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Спорт", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Спорт")).thenReturn(Optional.empty());
        when(groupStyleResolver.resolve("Спорт")).thenReturn(new GroupStyleResolver.GroupStyle("cyan", "run"));
        when(groupRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<GroupJpaEntity> groupCaptor = ArgumentCaptor.forClass(GroupJpaEntity.class);
        verify(groupRepository).save(groupCaptor.capture());
        assertThat(groupCaptor.getValue().getColor()).isEqualTo("cyan");
        assertThat(groupCaptor.getValue().getIcon()).isEqualTo("run");
    }

    @Test
    void update_recordsGroupChangeInAudit() {
        var entity = taskEntity();
        var existingGroup = new GroupJpaEntity();
        existingGroup.setId(UUID.randomUUID());
        existingGroup.setUserId(userId);
        existingGroup.setName("Работа");
        var request = new UpdateTaskRequest(null, null, null, null, null, null, "Работа", null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(groupRepository.findByUserIdAndName(userId, "Работа")).thenReturn(Optional.of(existingGroup));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        ArgumentCaptor<Map> deltaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(userId), eq(taskId), eq(AuditEventType.UPDATED), deltaCaptor.capture());
        assertThat(deltaCaptor.getValue()).containsEntry("groupId", existingGroup.getId());
    }

    @Test
    void complete_setsStatusDone() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.complete(userId, taskId);

        assertThat(entity.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(entity.getCompletedAt()).isNotNull();
    }

    @Test
    void complete_cancelsNotifications() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.complete(userId, taskId);

        verify(notificationService).cancelTaskNotifications(taskId);
    }

    @Test
    void delete_marksAsDeleted() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.delete(userId, taskId);

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();
    }

    @Test
    void delete_cancelsNotifications() {
        var entity = taskEntity();
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));

        taskService.delete(userId, taskId);

        verify(notificationService).cancelTaskNotifications(taskId);
    }

    @Test
    void update_cancelsNotifications_whenStatusBecomesCancelled() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, null, TaskStatus.CANCELLED, null, null, null, null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        verify(notificationService).cancelTaskNotifications(taskId);
        verify(notificationService, never()).scheduleTaskReminder(any(), any(), any(), any());
    }

    @Test
    void update_doesNotCancelNotifications_whenOnlyUnrelatedFieldChanges() {
        var entity = taskEntity();
        var request = new UpdateTaskRequest(null, null, TaskPriority.HIGH, null, null, null, null, null, null);
        var response = mockResponse(taskId, "задача");

        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenReturn(entity);
        when(taskMapper.toResponse(entity)).thenReturn(response);

        taskService.update(userId, taskId, request);

        verify(notificationService, never()).cancelTaskNotifications(any());
    }

    @Test
    void delete_throwsNotFound_whenMissing() {
        when(taskRepository.findByIdAndUserId(taskId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.delete(userId, taskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void findAssistantContext_returnsTasksFromRepository() {
        var entity = taskEntity();
        var response = mockResponse(taskId, "купить молоко");

        when(taskRepository.findAssistantContext(eq(userId), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.findAssistantContext(userId, 80);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("купить молоко");
    }

    @Test
    void findAssistantContext_passesLimitAsPageSize() {
        when(taskRepository.findAssistantContext(eq(userId), any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of());

        taskService.findAssistantContext(userId, 25);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAssistantContext(eq(userId), any(OffsetDateTime.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(25);
    }

    @Test
    void search_returnsMatchesByTitle() {
        var entity = new TaskJpaEntity();
        entity.setUserId(userId);
        entity.setTitle("Купить корм коту");
        var response = mockResponse(taskId, "Купить корм коту");

        when(taskRepository.search(eq(userId), eq("корм"), eq(false), any(Pageable.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.search(userId, "корм", false, 20);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("Купить корм коту");
    }

    @Test
    void search_returnsEmptyForBlankQuery() {
        var result = taskService.search(userId, "  ", false, 20);

        assertThat(result).isEmpty();
        verifyNoInteractions(taskRepository);
    }

    @Test
    void search_capsLimit() {
        taskService.search(userId, "корм", false, 500);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).search(any(), any(), anyBoolean(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void getStats_includesCompletedRowsReturnedByRepository() {
        var completedAt = OffsetDateTime.parse("2026-08-15T10:00:00Z");
        when(taskRepository.findStatsRows(eq(userId), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.<Object[]>of(new Object[] {
                        completedAt.minusDays(1),
                        completedAt,
                        null,
                        "DONE"
                }));

        var result = taskService.getStats(userId);

        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().getFirst().completedAt()).isEqualTo(completedAt.withOffsetSameInstant(ZoneOffset.UTC));
        assertThat(result.tasks().getFirst().status()).isEqualTo(TaskStatus.DONE);
    }

    @Test
    void transferOwnership_movesTasksGroupsAndTags() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(taskRepository.reassignOwner(from, to)).thenReturn(120);
        when(groupRepository.reassignOwner(from, to)).thenReturn(5);
        when(tagRepository.reassignOwner(from, to)).thenReturn(3);

        var result = taskService.transferOwnership(from, to);

        assertThat(result.tasks()).isEqualTo(120);
        assertThat(result.groups()).isEqualTo(5);
        assertThat(result.tags()).isEqualTo(3);
    }

    @Test
    void getUpcomingFocusTasks_returnsTasksFromRepository() {
        var entity = taskEntity();
        var response = mockResponse(taskId, "задача на следующей неделе");

        when(taskRepository.findUpcomingFocusTasks(eq(userId), eq(TaskStatus.DONE), any(OffsetDateTime.class)))
                .thenReturn(List.of(entity));
        when(taskMapper.toResponse(entity)).thenReturn(response);

        var result = taskService.getUpcomingFocusTasks(userId);

        assertThat(result.tasks()).containsExactly(response);
    }

    @Test
    void getUpcomingFocusTasks_capsAtThree() {
        var entities = List.of(taskEntity(), taskEntity(), taskEntity(), taskEntity());
        when(taskRepository.findUpcomingFocusTasks(eq(userId), eq(TaskStatus.DONE), any(OffsetDateTime.class)))
                .thenReturn(entities);
        when(taskMapper.toResponse(any())).thenReturn(mockResponse(taskId, "задача"));

        var result = taskService.getUpcomingFocusTasks(userId);

        assertThat(result.tasks()).hasSize(3);
    }

    private TaskJpaEntity taskEntity() {
        var e = new TaskJpaEntity();
        e.setUserId(userId);
        e.setTitle("задача");
        return e;
    }

    private TaskResponse mockResponse(UUID id, String title) {
        return new TaskResponse(id, title, null, null, TaskStatus.TODO,
                null, null, null, null, null, List.of(), null, null, null);
    }
}
