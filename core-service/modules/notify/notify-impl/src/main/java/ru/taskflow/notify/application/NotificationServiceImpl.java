package ru.taskflow.notify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationRepository;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сервис управления напоминаниями и уведомлениями.
 *
 * Планирует отправку напоминаний о задачах, управляет расписанием уведомлений,
 * отправляемых notification-worker через Quartz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final ScheduledNotificationRepository scheduledNotificationRepository;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void scheduleTaskReminder(UUID userId, UUID taskId, String title, OffsetDateTime deadline) {
        if (deadline == null) {
            return;
        }

        var telegramExternalId = userService.findExternalId(userId, IdentityProvider.TELEGRAM);
        if (telegramExternalId.isEmpty()) {
            log.warn("No telegram identity for user: {}", userId);
            return;
        }
        Long telegramChatId = Long.parseLong(telegramExternalId.get());

        ZoneId timezone = userService.getTimezone(userId);
        int offsetMinutes = userService.getSettings(userId).defaultReminderMinutes();

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime fireAt = deadline.minusMinutes(offsetMinutes);

        if (deadline.isBefore(now)) {
            log.debug("Deadline is in the past, skipping notification for task: {}", taskId);
            return;
        }

        if (fireAt.isBefore(now)) {
            fireAt = now.plusSeconds(5);
        }

        var notification = new ScheduledNotificationJpaEntity();
        notification.setUserId(userId);
        notification.setTaskId(taskId);
        notification.setTelegramChatId(telegramChatId);
        notification.setFireAt(fireAt);
        notification.setPayloadType("TASK_REMINDER");
        notification.setPayload(buildPayload(title, deadline, timezone));
        notification.setSent(false);
        notification.setRetryCount(0);

        scheduledNotificationRepository.save(notification);
        log.info("Scheduled notification for task {} at {}", taskId, fireAt);
    }

    @Override
    @Transactional
    public void cancelTaskNotifications(UUID taskId) {
        scheduledNotificationRepository.deleteUnsentByTaskId(taskId);
        log.debug("Cancelled unsent notifications for task: {}", taskId);
    }

    @Override
    @Transactional
    public int transferOwnership(UUID from, UUID to) {
        var targetTelegram = userService.findExternalId(to, IdentityProvider.TELEGRAM);
        if (targetTelegram.isPresent()) {
            return scheduledNotificationRepository.reassignOwner(from, to, Long.parseLong(targetTelegram.get()));
        }
        return scheduledNotificationRepository.reassignOwnerKeepChatId(from, to);
    }

    private String buildPayload(String title, OffsetDateTime deadline, ZoneId timezone) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("taskTitle", title);
            payload.put("deadline", deadline.toString());
            payload.put("timezone", timezone.getId());
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize notification payload", e);
            return "{}";
        }
    }
}
