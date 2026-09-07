package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.user.api.UserService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class FocusHintAuditIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuditService auditService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private UserService userService;

    @Test
    void focusHintEvents_roundTripThroughPostgresConstraint() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        UUID userId = userService.findOrCreateByTelegram(
                telegramId, "focus_audit_" + telegramId, "Test", "User").id();
        UUID taskId = taskService.createQuick(userId,
                new CreateTaskRequest("Проверить событие", null, null, null,
                        null, null, List.of(), null, null)).id();

        auditService.record(userId, taskId, AuditEventType.FOCUS_HINT_SHOWN, null);
        auditService.record(userId, taskId, AuditEventType.STARTED_AFTER_HINT, null);

        assertThat(auditService.getHistory(taskId, userId))
                .extracting(event -> event.eventType())
                .contains(AuditEventType.FOCUS_HINT_SHOWN.name(), AuditEventType.STARTED_AFTER_HINT.name());
    }
}
