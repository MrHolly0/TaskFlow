package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import ru.taskflow.notificationworker.infrastructure.db.ScheduledNotificationPoller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private final ScheduledNotificationPoller poller;
    private final List<NotificationSender> senders;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelayString = "${app.notification.poll-interval-ms:30000}")
    @Transactional
    public void processNotifications() {
        List<PendingNotification> notifications = poller.pollPending();

        for (PendingNotification notification : notifications) {
            try {
                dispatchNotification(notification);
                poller.markAsSent(notification.id());
            } catch (PushSubscriptionGoneException e) {
                // Не ошибка очереди — endpoint отозван, повторять нечего.
                // markAsSent здесь не «доставлено», а «закрыть строку без
                // повтора»: отдельного терминального статуса под это заводить
                // не стали, retry всё равно не помог бы.
                log.info("{}, удаляю подписку", e.getMessage());
                jdbcTemplate.update("DELETE FROM push_subscriptions WHERE id = ?",
                        UUID.fromString(notification.destination()));
                poller.markAsSent(notification.id());
            } catch (Exception e) {
                log.error("Failed to dispatch notification {}: {}", notification.id(), e.getMessage());
                poller.incrementRetryCount(notification.id());
            }
        }
    }

    private void dispatchNotification(PendingNotification notification) {
        if ("TASK_REMINDER".equals(notification.payloadType())) {
            dispatchTaskReminder(notification);
        } else {
            log.warn("Unknown notification type: {}", notification.payloadType());
        }
    }

    private void dispatchTaskReminder(PendingNotification notification) {
        NotificationSender sender = senders.stream()
                .filter(s -> s.supports(notification.channel()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Нет отправителя для канала " + notification.channel()));

        Map<String, Object> payload;
        try {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) objectMapper.readValue(notification.payload(), Map.class);
            payload = parsed;
        } catch (Exception e) {
            log.error("Failed to parse task reminder payload: {}", notification.payload(), e);
            throw new RuntimeException("Failed to dispatch task reminder", e);
        }

        // Вызов отправителя — вне try/catch по парсингу: PushSubscriptionGoneException
        // и любые другие сигналы отправителя должны дойти до processNotifications
        // как есть, не потеряться, завёрнутые в общий RuntimeException.
        String title = (String) payload.get("taskTitle");
        String deadline = (String) payload.get("deadline");
        String timezone = (String) payload.get("timezone");
        sender.sendTaskReminder(notification.destination(), title, deadline, timezone);
    }
}
