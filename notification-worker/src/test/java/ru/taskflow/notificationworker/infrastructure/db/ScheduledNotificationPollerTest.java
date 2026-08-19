package ru.taskflow.notificationworker.infrastructure.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * На H2/моках ошибка не проявляется — драйвер H2 принимает строку на месте
 * uuid без вопросов. Настоящий Postgres её отвергал: markAsSent/incrementRetryCount
 * передавали notificationId.toString() в колонку uuid, запрос падал, а поскольку
 * отправка идёт до пометки, сообщение уходило повторно на каждом опросе.
 * Отсюда и Testcontainers — тест должен видеть то же поведение, что стенд.
 */
@Testcontainers
class ScheduledNotificationPollerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbcTemplate;
    private ScheduledNotificationPoller poller;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        poller = new ScheduledNotificationPoller(jdbcTemplate);
        // @Value-поля не заполняются вне спринг-контекста — без этого
        // batchSize/maxRetries остаются нулями, и pollPending() ничего не находит.
        ReflectionTestUtils.setField(poller, "batchSize", 50);
        ReflectionTestUtils.setField(poller, "maxRetries", 3);

        // Только scheduled_notifications, без FK на users/tasks — этот класс
        // работает с одной таблицей и не знает о существовании остальной схемы.
        jdbcTemplate.execute("DROP TABLE IF EXISTS scheduled_notifications");
        jdbcTemplate.execute("""
                CREATE TABLE scheduled_notifications (
                    id UUID PRIMARY KEY,
                    channel VARCHAR(16) NOT NULL,
                    destination VARCHAR(320) NOT NULL,
                    fire_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    payload_type VARCHAR(32) NOT NULL,
                    payload JSONB,
                    sent BOOLEAN DEFAULT FALSE,
                    sent_at TIMESTAMP WITH TIME ZONE,
                    retry_count INT DEFAULT 0
                )
                """);
    }

    @Test
    void markAsSent_excludesNotificationFromNextPoll() {
        UUID id = insertPending();

        var polled = poller.pollPending();
        assertThat(polled).extracting(PendingNotification::id).containsExactly(id);

        poller.markAsSent(id);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sent FROM scheduled_notifications WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sent_at FROM scheduled_notifications WHERE id = ?", OffsetDateTime.class, id))
                .isNotNull();

        var polledAgain = poller.pollPending();
        assertThat(polledAgain).isEmpty();
    }

    @Test
    void incrementRetryCount_updatesRowWithoutTypeError() {
        UUID id = insertPending();

        poller.incrementRetryCount(id);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM scheduled_notifications WHERE id = ?", Integer.class, id))
                .isEqualTo(1);
    }

    private UUID insertPending() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO scheduled_notifications (id, channel, destination, fire_at, payload_type, payload, sent, retry_count)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, false, 0)
                """,
                id, "TELEGRAM", "12345", OffsetDateTime.now().minusMinutes(1), "TASK_REMINDER", "{}");
        return id;
    }
}
