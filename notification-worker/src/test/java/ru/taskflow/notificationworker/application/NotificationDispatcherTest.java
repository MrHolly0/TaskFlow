package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import ru.taskflow.notificationworker.infrastructure.db.ScheduledNotificationPoller;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private final ScheduledNotificationPoller poller = mock(ScheduledNotificationPoller.class);
    private final NotificationSender telegramSender = mock(NotificationSender.class);
    private final NotificationSender emailSender = mock(NotificationSender.class);
    private final NotificationSender pushSender = mock(NotificationSender.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NotificationDispatcher dispatcher =
            new NotificationDispatcher(poller, List.of(telegramSender, emailSender, pushSender),
                    new ObjectMapper(), jdbcTemplate);

    private PendingNotification pending(String channel, String destination, String payload) {
        return new PendingNotification(UUID.randomUUID(), channel, destination, "TASK_REMINDER", payload);
    }

    @Test
    void processNotifications_dispatchesToSenderThatSupportsTheChannelAndMarksSent() {
        PendingNotification notification = pending("TELEGRAM", "12345",
                "{\"taskTitle\":\"купить молоко\",\"deadline\":\"2030-01-01T10:00:00+03:00\",\"timezone\":\"Europe/Moscow\"}");
        when(poller.pollPending()).thenReturn(List.of(notification));
        when(telegramSender.supports("TELEGRAM")).thenReturn(true);
        when(emailSender.supports("TELEGRAM")).thenReturn(false);

        dispatcher.processNotifications();

        verify(telegramSender).sendTaskReminder("12345", "купить молоко", "2030-01-01T10:00:00+03:00", "Europe/Moscow");
        verify(emailSender, never()).sendTaskReminder(any(), any(), any(), any());
        verify(poller).markAsSent(notification.id());
        verify(poller, never()).incrementRetryCount(notification.id());
    }

    @Test
    void processNotifications_dispatchesEmailToTheEmailSender() {
        PendingNotification notification = pending("EMAIL", "user@example.com",
                "{\"taskTitle\":\"купить молоко\",\"deadline\":null,\"timezone\":\"Europe/Moscow\"}");
        when(poller.pollPending()).thenReturn(List.of(notification));
        when(telegramSender.supports("EMAIL")).thenReturn(false);
        when(emailSender.supports("EMAIL")).thenReturn(true);

        dispatcher.processNotifications();

        verify(emailSender).sendTaskReminder("user@example.com", "купить молоко", null, "Europe/Moscow");
        verify(telegramSender, never()).sendTaskReminder(any(), any(), any(), any());
        verify(poller).markAsSent(notification.id());
    }

    @Test
    void processNotifications_retriesWhenNoSenderSupportsTheChannel() {
        PendingNotification notification = pending("SMS", "+70000000000", "{}");
        when(poller.pollPending()).thenReturn(List.of(notification));
        when(telegramSender.supports("SMS")).thenReturn(false);
        when(emailSender.supports("SMS")).thenReturn(false);

        dispatcher.processNotifications();

        verify(telegramSender, never()).sendTaskReminder(any(), any(), any(), any());
        verify(emailSender, never()).sendTaskReminder(any(), any(), any(), any());
        verify(poller).incrementRetryCount(notification.id());
        verify(poller, never()).markAsSent(notification.id());
    }

    @Test
    void processNotifications_oneChannelsFailureDoesNotBlockTheOther() {
        // Строка на канал — отказ Telegram (например бот заблокирован) не должен
        // помешать доставке того же напоминания по почте: это независимые строки
        // очереди, не один запрос с веером получателей.
        PendingNotification telegramRow = pending("TELEGRAM", "12345",
                "{\"taskTitle\":\"купить молоко\",\"deadline\":null,\"timezone\":\"Europe/Moscow\"}");
        PendingNotification emailRow = pending("EMAIL", "user@example.com",
                "{\"taskTitle\":\"купить молоко\",\"deadline\":null,\"timezone\":\"Europe/Moscow\"}");
        when(poller.pollPending()).thenReturn(List.of(telegramRow, emailRow));
        when(telegramSender.supports("TELEGRAM")).thenReturn(true);
        when(emailSender.supports("EMAIL")).thenReturn(true);
        doThrow(new RuntimeException("бот заблокирован пользователем"))
                .when(telegramSender).sendTaskReminder(any(), any(), any(), any());

        dispatcher.processNotifications();

        verify(poller).incrementRetryCount(telegramRow.id());
        verify(poller, never()).markAsSent(telegramRow.id());
        verify(emailSender).sendTaskReminder("user@example.com", "купить молоко", null, "Europe/Moscow");
        verify(poller).markAsSent(emailRow.id());
        verify(poller, never()).incrementRetryCount(emailRow.id());
    }

    @Test
    void processNotifications_goneSubscription_deletesItAndMarksSentWithoutRetry() {
        UUID subscriptionId = UUID.randomUUID();
        PendingNotification notification = pending("WEB_PUSH", subscriptionId.toString(),
                "{\"taskTitle\":\"купить молоко\",\"deadline\":null,\"timezone\":\"Europe/Moscow\"}");
        when(poller.pollPending()).thenReturn(List.of(notification));
        when(pushSender.supports("WEB_PUSH")).thenReturn(true);
        doThrow(new PushSubscriptionGoneException(subscriptionId.toString()))
                .when(pushSender).sendTaskReminder(any(), any(), any(), any());

        dispatcher.processNotifications();

        verify(jdbcTemplate).update(eq("DELETE FROM push_subscriptions WHERE id = ?"), eq(subscriptionId));
        verify(poller).markAsSent(notification.id());
        verify(poller, never()).incrementRetryCount(notification.id());
    }

    @Test
    void processNotifications_retriesOnMalformedPayload() {
        PendingNotification notification = pending("TELEGRAM", "12345", "это не json");
        when(poller.pollPending()).thenReturn(List.of(notification));
        when(telegramSender.supports("TELEGRAM")).thenReturn(true);

        dispatcher.processNotifications();

        verify(poller).incrementRetryCount(notification.id());
        verify(poller, never()).markAsSent(notification.id());
    }
}
