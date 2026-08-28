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
import ru.taskflow.task.api.RecurrenceType;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.RecurrenceRule;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.user.api.UserService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной путь повтора на настоящем Postgres (реальные миграции, реальный
 * TaskServiceImpl) — путь целиком, не отдельный запрос, как в репозиторных
 * тестах task-impl. Б1/Б2 задания "прокрутка повторов, сквозные сценарии".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TaskRecurrenceIntegrationTest {

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

    private java.util.UUID newUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return userService.findOrCreateByTelegram(telegramId, "recurrence_it_" + telegramId, "Test", "User").id();
    }

    @Test
    void recurringTask_closesAndProducesNextOccurrence_withInheritedFieldsAndOwnReminder() {
        var userId = newUser();
        var deadline = OffsetDateTime.now().plusDays(3).withNano(0);
        var rule = new RecurrenceRule(RecurrenceType.DAILY, 1, null, null, null);
        var created = taskService.create(userId, new CreateTaskRequest(
                "Полить цветы", "раз в день", TaskPriority.HIGH, deadline, null, "Дом",
                List.of("быт"), 15, TaskSource.MANUAL, rule));

        assertThat(created.recurrence()).isNotNull();
        // create() возвращает reminders пустым списком всегда (TaskMapper.toResponse
        // не подставляет настоящий список нигде, кроме findById/findAll) — реальные
        // напоминания проверяем через отдельное чтение, а не через ответ create().
        var createdWithReminders = taskService.findById(userId, created.id());
        assertThat(createdWithReminders.reminders()).isNotEmpty(); // planForDeadline при создании со сроком

        taskService.complete(userId, created.id());

        var page = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20));
        var nextOccurrence = page.getContent().stream()
                .filter(t -> t.title().equals("Полить цветы") && !t.id().equals(created.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("следующее вхождение не найдено"));

        // верная дата — ровно на день позже исходного срока
        assertThat(nextOccurrence.deadline()).isEqualTo(deadline.plusDays(1));
        // унаследованные поля
        assertThat(nextOccurrence.description()).isEqualTo("раз в день");
        assertThat(nextOccurrence.priority()).isEqualTo(TaskPriority.HIGH);
        assertThat(nextOccurrence.groupName()).isEqualTo("Дом");
        assertThat(nextOccurrence.tags()).containsExactly("быт");
        assertThat(nextOccurrence.estimateMinutes()).isEqualTo(15);
        // не унаследовано — источник у нового вхождения свой
        assertThat(nextOccurrence.source()).isEqualTo(TaskSource.RECURRENCE);
        // правило перенесено на новое вхождение
        assertThat(nextOccurrence.recurrence()).isNotNull();
        assertThat(nextOccurrence.recurrence().type()).isEqualTo(RecurrenceType.DAILY);
        // у нового вхождения своё напоминание, не то же самое, что у исходного
        assertThat(nextOccurrence.reminders()).isNotEmpty();
        assertThat(nextOccurrence.reminders().getFirst().id()).isNotEqualTo(createdWithReminders.reminders().getFirst().id());

        // отмена нового вхождения не порождает третье
        taskService.update(userId, nextOccurrence.id(),
                new UpdateTaskRequest(null, null, null, TaskStatus.CANCELLED, null, null, null, null, null));

        var pageAfterCancel = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20));
        long occurrences = pageAfterCancel.getContent().stream()
                .filter(t -> t.title().equals("Полить цветы"))
                .count();
        assertThat(occurrences).isEqualTo(2);
    }

    @Test
    void recurringTask_closedManyPeriodsLate_producesOccurrenceInTheFuture() {
        var userId = newUser();
        // Еженедельная задача, просроченная на три недели к моменту закрытия —
        // один шаг от опоздавшей даты дал бы вхождение, тоже просроченное на
        // две недели (Блок А). Якорь дня недели должен сохраниться.
        var lateDeadline = OffsetDateTime.now().minusDays(21).withNano(0);
        var rule = new RecurrenceRule(RecurrenceType.WEEKLY, 1, null, null, null);
        var created = taskService.create(userId, new CreateTaskRequest(
                "Еженедельный отчёт", null, TaskPriority.MEDIUM, lateDeadline, null, null,
                List.of(), null, TaskSource.MANUAL, rule));
        var originalDayOfWeek = lateDeadline.getDayOfWeek();

        taskService.complete(userId, created.id());

        var page = taskService.findAll(userId, new TaskFilterRequest(null, null, null, null), PageRequest.of(0, 20));
        var nextOccurrence = page.getContent().stream()
                .filter(t -> t.title().equals("Еженедельный отчёт") && !t.id().equals(created.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("следующее вхождение не найдено"));

        assertThat(nextOccurrence.deadline()).isAfter(OffsetDateTime.now());
        assertThat(nextOccurrence.deadline().getDayOfWeek()).isEqualTo(originalDayOfWeek);
    }
}
