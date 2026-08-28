package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationRepository;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.user.api.UserService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозные сценарии на настоящем Postgres — напоминания и разведение срока и
 * дня исполнения по всему пути, не по отдельному запросу. Б3/Б4/Б7 задания
 * "прокрутка повторов, сквозные сценарии".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TaskReminderAndScheduleIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TaskService taskService;
    @Autowired
    private UserService userService;
    @Autowired
    private ScheduledNotificationRepository scheduledNotificationRepository;

    private UUID newUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return userService.findOrCreateByTelegram(telegramId, "reminder_it_" + telegramId, "Test", "User").id();
    }

    @Test
    void twoReminders_cancellingOne_leavesTheOtherIntact_noOrphanNotification() {
        var userId = newUser();
        var created = taskService.create(userId, new CreateTaskRequest(
                "Позвонить в банк", null, TaskPriority.MEDIUM, null, null, null, List.of(), null, TaskSource.MANUAL));

        var fireAt1 = OffsetDateTime.now().plusHours(2).withNano(0);
        var fireAt2 = OffsetDateTime.now().plusDays(1).withNano(0);
        taskService.scheduleReminder(userId, created.id(), fireAt1);
        taskService.scheduleReminder(userId, created.id(), fireAt2);

        var beforeCancel = taskService.getReminders(userId, created.id());
        assertThat(beforeCancel).hasSize(2);
        var toCancel = beforeCancel.stream().filter(r -> r.fireAt().isEqual(fireAt1)).findFirst().orElseThrow();
        var toKeep = beforeCancel.stream().filter(r -> r.fireAt().isEqual(fireAt2)).findFirst().orElseThrow();

        taskService.cancelReminder(userId, created.id(), toCancel.id());

        var afterCancel = taskService.getReminders(userId, created.id());
        assertThat(afterCancel).hasSize(1);
        assertThat(afterCancel.getFirst().id()).isEqualTo(toKeep.id());
        assertThat(afterCancel.getFirst().status()).isEqualTo("PENDING");

        // снятое напоминание не осталось сиротой в scheduled_notifications —
        // ради этой гарантии заводилась отдельная таблица reminders (Пункт Г
        // прошлого задания)
        var notificationsForTask = scheduledNotificationRepository.findAll().stream()
                .filter(n -> n.getTaskId().equals(created.id()))
                .toList();
        assertThat(notificationsForTask).hasSize(1);
        assertThat(notificationsForTask.getFirst().getReminderId()).isEqualTo(toKeep.id());
    }

    @Test
    void snoozingTaskWithRealDeadline_movesPlannedDateOnly_deadlineAndOverdueUnaffected() {
        var userId = newUser();
        var futureDeadline = OffsetDateTime.now().plusDays(5).withNano(0);
        var created = taskService.create(userId, new CreateTaskRequest(
                "Сдать курсовую", null, TaskPriority.HIGH, futureDeadline, null, null, List.of(), null, TaskSource.MANUAL));

        var tomorrow = OffsetDateTime.now().plusDays(1).withNano(0);
        var updated = taskService.update(userId, created.id(),
                new UpdateTaskRequest(null, null, null, null, null, null, null, null, null, tomorrow));

        assertThat(updated.deadline()).isEqualTo(futureDeadline);
        assertThat(updated.plannedDate()).isEqualTo(tomorrow);

        var digest = taskService.getDigest(userId, java.time.LocalDate.now());
        assertThat(digest.overdueTasks()).isZero();
    }

    @Test
    void reminders_areReturnedTheSameWay_inAllTasksView_focusView_andUpcomingView() {
        var userId = newUser();
        var todayLater = OffsetDateTime.now().plusHours(3).withNano(0);
        var nextWeek = OffsetDateTime.now().plusDays(7).withNano(0);

        var todayTask = taskService.create(userId, new CreateTaskRequest(
                "Задача на сегодня", null, TaskPriority.MEDIUM, todayLater, null, null, List.of(), null, TaskSource.MANUAL));
        var laterTask = taskService.create(userId, new CreateTaskRequest(
                "Задача через неделю", null, TaskPriority.MEDIUM, nextWeek, null, null, List.of(), null, TaskSource.MANUAL));

        // Регрессия на дефект: /tasks/focus и /tasks/focus/upcoming не подтягивали
        // напоминания вовсе (найдено вручную при проверке адаптива, исправлено
        // в прошлом задании) — здесь это закреплено тестом.
        var allTasks = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20));
        var focusTasks = taskService.getFocusTasks(userId, null).tasks();
        var upcomingTasks = taskService.getUpcomingFocusTasks(userId, null).tasks();

        var allTasksTodayReminders = allTasks.getContent().stream()
                .filter(t -> t.id().equals(todayTask.id())).findFirst().orElseThrow().reminders();
        var focusTodayReminders = focusTasks.stream()
                .filter(t -> t.id().equals(todayTask.id())).findFirst().orElseThrow().reminders();
        assertThat(focusTodayReminders).isNotEmpty();
        assertThat(focusTodayReminders).extracting(r -> r.id()).containsExactlyElementsOf(
                allTasksTodayReminders.stream().map(r -> r.id()).toList());

        var allTasksLaterReminders = allTasks.getContent().stream()
                .filter(t -> t.id().equals(laterTask.id())).findFirst().orElseThrow().reminders();
        var upcomingLaterReminders = upcomingTasks.stream()
                .filter(t -> t.id().equals(laterTask.id())).findFirst().orElseThrow().reminders();
        assertThat(upcomingLaterReminders).isNotEmpty();
        assertThat(upcomingLaterReminders).extracting(r -> r.id()).containsExactlyElementsOf(
                allTasksLaterReminders.stream().map(r -> r.id()).toList());
    }
}
