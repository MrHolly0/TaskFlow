package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.notificationworker.infrastructure.db.PendingNotification;
import ru.taskflow.notificationworker.infrastructure.db.ScheduledNotificationPoller;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private final ScheduledNotificationPoller poller = mock(ScheduledNotificationPoller.class);
    private final TelegramNotificationSender sender = mock(TelegramNotificationSender.class);
    private final NotificationDispatcher dispatcher =
            new NotificationDispatcher(poller, sender, new ObjectMapper());

    private PendingNotification pending(String channel, String destination, String payload) {
        return new PendingNotification(UUID.randomUUID(), channel, destination, "TASK_REMINDER", payload);
    }

    @Test
    void processNotifications_dispatchesTelegramNotificationAndMarksSent() {
        PendingNotification notification = pending("TELEGRAM", "12345",
                "{\"taskTitle\":\"купить молоко\",\"deadline\":\"2030-01-01T10:00:00+03:00\",\"timezone\":\"Europe/Moscow\"}");
        when(poller.pollPending()).thenReturn(List.of(notification));

        dispatcher.processNotifications();

        verify(sender).sendTaskReminder(12345L, "купить молоко", "2030-01-01T10:00:00+03:00", "Europe/Moscow");
        verify(poller).markAsSent(notification.id());
        verify(poller, never()).incrementRetryCount(notification.id());
    }

    @Test
    void processNotifications_retriesInsteadOfSilentlyDroppingUnimplementedChannel() {
        // Пока нет отправителя писем — не тишина, а retry_count, чтобы строка
        // не потерялась и её можно было доотправить, когда отправитель появится.
        PendingNotification notification = pending("EMAIL", "user@example.com", "{}");
        when(poller.pollPending()).thenReturn(List.of(notification));

        dispatcher.processNotifications();

        verify(sender, never()).sendTaskReminder(anyLong(), any(), any(), any());
        verify(poller).incrementRetryCount(notification.id());
        verify(poller, never()).markAsSent(notification.id());
    }

    @Test
    void processNotifications_retriesOnMalformedPayload() {
        PendingNotification notification = pending("TELEGRAM", "12345", "это не json");
        when(poller.pollPending()).thenReturn(List.of(notification));

        dispatcher.processNotifications();

        verify(poller).incrementRetryCount(notification.id());
        verify(poller, never()).markAsSent(notification.id());
    }
}
