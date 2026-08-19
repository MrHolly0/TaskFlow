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
import java.util.EnumMap;
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

        OffsetDateTime now = OffsetDateTime.now();
        if (deadline.isBefore(now)) {
            log.debug("Deadline is in the past, skipping notification for task: {}", taskId);
            return;
        }

        // Собираем адресатов по каналам до вычисления таймзоны/срока — если
        // идентичностей нет вообще, не тратим лишний вызов userService на них.
        Map<IdentityProvider, String> destinations = new EnumMap<>(IdentityProvider.class);
        for (IdentityProvider channel : IdentityProvider.values()) {
            userService.findExternalId(userId, channel).ifPresent(id -> destinations.put(channel, id));
        }
        if (destinations.isEmpty()) {
            log.warn("No notification identity (telegram or email) for user: {}", userId);
            return;
        }

        ZoneId timezone = userService.getTimezone(userId);
        int offsetMinutes = userService.getSettings(userId).defaultReminderMinutes();
        OffsetDateTime computedFireAt = deadline.minusMinutes(offsetMinutes);
        OffsetDateTime fireAt = computedFireAt.isBefore(now) ? now.plusSeconds(5) : computedFireAt;
        String payload = buildPayload(title, deadline, timezone);

        // Строка на канал, а не веер внутри одной строки: sent и retry_count
        // живут в строке, и только так отказ одного канала (например письмо
        // не ушло) не мешает доставке по другому.
        destinations.forEach((channel, destination) ->
                scheduleForChannel(userId, taskId, channel, destination, fireAt, payload));
    }

    private void scheduleForChannel(UUID userId, UUID taskId, IdentityProvider channel, String destination,
                                     OffsetDateTime fireAt, String payload) {
        var notification = new ScheduledNotificationJpaEntity();
        notification.setUserId(userId);
        notification.setTaskId(taskId);
        notification.setChannel(channel);
        notification.setDestination(destination);
        notification.setFireAt(fireAt);
        notification.setPayloadType("TASK_REMINDER");
        notification.setPayload(payload);
        notification.setSent(false);
        notification.setRetryCount(0);

        scheduledNotificationRepository.save(notification);
        log.info("Scheduled {} notification for task {} at {}", channel, taskId, fireAt);
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
        String telegramDestination = userService.findExternalId(to, IdentityProvider.TELEGRAM).orElse(null);
        String emailDestination = userService.findExternalId(to, IdentityProvider.EMAIL).orElse(null);
        return scheduledNotificationRepository.reassignOwner(from, to, telegramDestination, emailDestination);
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
