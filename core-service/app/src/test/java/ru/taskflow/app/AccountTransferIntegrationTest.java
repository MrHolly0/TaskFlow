package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.app.application.AccountTransferService;
import ru.taskflow.notify.api.NotificationChannel;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationRepository;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.UserService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Прогон на выдуманных, но реалистично устроенных данных (реальные сервисы,
 * реальные миграции на Testcontainers Postgres, не моки) — сотня с лишним
 * задач с группами, метками, напоминаниями и историей аудита. Отдельно от
 * этого — прогон на копии реальной базы (pg_dump со стенда, аккаунт со 115
 * задачами), который в юнит-тест не завести: тот проверяется вручную.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AccountTransferIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AccountTransferService transferService;
    @Autowired
    private UserService userService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ScheduledNotificationRepository scheduledNotificationRepository;

    @Test
    void transfer_movesEverythingLinkedAndStaysIdempotentOnRepeat() {
        // Реалистичный сценарий из плана: старая учётка — Telegram, новая —
        // почта, ещё без своего Telegram. Если бы у target уже был Telegram,
        // перенос идентичности намеренно не тронул бы её (см. UserIdentityRepository.reassignOwner) —
        // это отдельный тест, здесь — основной путь.
        long sourceTelegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        var source = userService.findOrCreateByTelegram(sourceTelegramId, "source_user", "Source", "User");
        var target = userService.findOrCreateByIdentity(IdentityProvider.EMAIL, "target@example.com",
                new UserProfile("target_user", "Target", "User", null));

        int taskCount = 120;
        int expectedNotifications = 0;
        for (int i = 0; i < taskCount; i++) {
            String groupName = "Группа " + (i % 5);
            List<String> tags = i % 3 == 0 ? List.of("важное") : List.of();
            OffsetDateTime deadline = i % 4 == 0 ? OffsetDateTime.now().plusDays(1) : null;
            TaskResponse created = taskService.create(source.id(), new CreateTaskRequest(
                    "Задача " + i, null, TaskPriority.MEDIUM, deadline, null, groupName, tags, null, TaskSource.MANUAL));
            if (deadline != null) {
                expectedNotifications++;
                notificationService.scheduleTaskReminder(source.id(), created.id(), created.title(), deadline);
            }
        }

        var result = transferService.transfer(source.id(), target.id());

        assertThat(result.tasks()).isEqualTo(taskCount);
        assertThat(result.groups()).isEqualTo(5);
        assertThat(result.tags()).isEqualTo(1);
        assertThat(result.notifications()).isEqualTo(expectedNotifications);
        assertThat(result.auditEvents()).isEqualTo(taskCount);
        assertThat(result.identities()).isEqualTo(1);

        // исходная учётка не удалена
        assertThat(userService.findById(source.id())).isNotNull();

        // напоминания указывают на chat_id учётки-получателя, а не прежней
        var targetChatId = userService.findExternalId(target.id(), IdentityProvider.TELEGRAM).orElseThrow();
        var movedNotifications = scheduledNotificationRepository.findAll().stream()
                .filter(n -> n.getUserId().equals(target.id()))
                .toList();
        assertThat(movedNotifications).hasSize(expectedNotifications);
        assertThat(movedNotifications).allSatisfy(n -> {
            assertThat(n.getChannel()).isEqualTo(NotificationChannel.TELEGRAM);
            assertThat(n.getDestination()).isEqualTo(targetChatId);
        });

        // повторный вызов после переноса ничего не находит — не дублирует
        var repeat = transferService.transfer(source.id(), target.id());

        assertThat(repeat.tasks()).isZero();
        assertThat(repeat.groups()).isZero();
        assertThat(repeat.tags()).isZero();
        assertThat(repeat.notifications()).isZero();
        assertThat(repeat.auditEvents()).isZero();
        assertThat(repeat.identities()).isZero();
    }

    @Test
    void transfer_doesNotMoveIdentity_whenTargetAlreadyHasSameProvider() {
        long sourceTelegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        long targetTelegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        var source = userService.findOrCreateByTelegram(sourceTelegramId, "source_user2", "Source", "User");
        var target = userService.findOrCreateByTelegram(targetTelegramId, "target_user2", "Target", "User");

        var result = transferService.transfer(source.id(), target.id());

        assertThat(result.identities()).isZero();
        assertThat(userService.listIdentities(source.id())).hasSize(1);
        assertThat(userService.listIdentities(target.id())).hasSize(1);
    }
}
