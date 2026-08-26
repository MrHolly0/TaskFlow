package ru.taskflow.task.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.infrastructure.persistence.ReminderJpaEntity;
import ru.taskflow.task.infrastructure.persistence.ReminderRepository;
import ru.taskflow.task.infrastructure.persistence.ReminderStatus;
import ru.taskflow.task.infrastructure.persistence.TaskJpaEntity;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskReminderServiceTest {

    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private UserService userService;

    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-26T12:00:00+03:00");
    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.ofHours(3));

    private TaskReminderService service;

    @BeforeEach
    void setUp() {
        service = new TaskReminderService(reminderRepository, notificationService, userService, clock);
    }

    private TaskJpaEntity task(TaskPriority priority, OffsetDateTime deadline) {
        var task = new TaskJpaEntity();
        task.setId(UUID.randomUUID());
        task.setUserId(userId);
        task.setTitle("проверить духовку");
        task.setPriority(priority);
        task.setDeadline(deadline);
        return task;
    }

    private UserSettingsDto settings(int defaultReminderMinutes, boolean urgentExtraReminder) {
        return new UserSettingsDto(true, true, true, true, defaultReminderMinutes, urgentExtraReminder,
                "groq", null, "SILENCE", "SILENCE", "Europe/Moscow", "user");
    }

    // --- А4 ---

    @Test
    void planForDeadline_computedTimeInPast_deadlineInFuture_firesAtDeadline() {
        // «напомни через 30 минут проверить духовку» при настройке «за час»:
        // расчётное время (дедлайн минус час) уже прошло, сам срок — ещё нет.
        OffsetDateTime deadline = now.plusMinutes(30);
        var task = task(TaskPriority.MEDIUM, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, true));

        service.planForDeadline(userId, task);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getFireAt()).isEqualTo(deadline);
        assertThat(captor.getValue().getStatus()).isEqualTo(ReminderStatus.PENDING);
        verify(notificationService).scheduleReminder(userId, task.getId(), task.getTitle(), deadline, deadline);
    }

    @Test
    void planForDeadline_computedTimeAndDeadlineBothInPast_doesNotSchedule() {
        OffsetDateTime deadline = now.minusMinutes(5);
        var task = task(TaskPriority.MEDIUM, deadline);

        service.planForDeadline(userId, task);

        verify(reminderRepository, never()).save(any());
        verify(notificationService, never()).scheduleReminder(any(), any(), any(), any(), any());
        verify(userService, never()).getSettings(any());
    }

    @Test
    void planForDeadline_noDeadline_doesNotSchedule() {
        var task = task(TaskPriority.MEDIUM, null);

        service.planForDeadline(userId, task);

        verify(reminderRepository, never()).save(any());
        verify(notificationService, never()).scheduleReminder(any(), any(), any(), any(), any());
    }

    // --- А6 ---

    @Test
    void planForDeadline_urgentAndExtraReminderEnabled_schedulesSecondReminder() {
        OffsetDateTime deadline = now.plusDays(1);
        var task = task(TaskPriority.URGENT, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, true));

        service.planForDeadline(userId, task);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ReminderJpaEntity::getFireAt)
                .containsExactlyInAnyOrder(deadline.minusMinutes(60), deadline.minusMinutes(15));
        verify(notificationService, times(2)).scheduleReminder(eq(userId), eq(task.getId()), eq(task.getTitle()), any(), eq(deadline));
    }

    @Test
    void planForDeadline_urgentButExtraReminderSettingOff_schedulesOnlyDefaultReminder() {
        OffsetDateTime deadline = now.plusDays(1);
        var task = task(TaskPriority.URGENT, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, false));

        service.planForDeadline(userId, task);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFireAt()).isEqualTo(deadline.minusMinutes(60));
    }

    @Test
    void planForDeadline_notUrgent_schedulesOnlyDefaultReminderEvenWithExtraReminderEnabled() {
        OffsetDateTime deadline = now.plusDays(1);
        var task = task(TaskPriority.MEDIUM, deadline);
        when(userService.getSettings(userId)).thenReturn(settings(60, true));

        service.planForDeadline(userId, task);

        verify(reminderRepository, times(1)).save(any());
    }

    // --- Б2 ---

    @Test
    void createStandaloneReminder_taskWithoutDeadline_schedulesReminderAndLeavesDeadlineNull() {
        var task = task(TaskPriority.MEDIUM, null);
        OffsetDateTime fireAt = now.plusDays(1).withHour(9).withMinute(0);

        service.createStandaloneReminder(userId, task, fireAt);

        ArgumentCaptor<ReminderJpaEntity> captor = ArgumentCaptor.forClass(ReminderJpaEntity.class);
        verify(reminderRepository).save(captor.capture());
        assertThat(captor.getValue().getFireAt()).isEqualTo(fireAt);
        assertThat(captor.getValue().getTask()).isSameAs(task);
        verify(notificationService).scheduleReminder(userId, task.getId(), task.getTitle(), fireAt, null);
        assertThat(task.getDeadline()).isNull();
    }

    // --- отмена (Б4: своя таблица, задачу не трогает) ---

    @Test
    void cancelForTask_cancelsPendingRemindersAndScheduledNotifications() {
        UUID taskId = UUID.randomUUID();

        service.cancelForTask(taskId);

        verify(reminderRepository).cancelPendingByTaskId(taskId);
        verify(notificationService).cancelTaskNotifications(taskId);
    }
}
