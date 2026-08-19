package ru.taskflow.notify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationRepository;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private ScheduledNotificationRepository scheduledNotificationRepository;
    @Mock
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationServiceImpl notificationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(
                scheduledNotificationRepository, userService, objectMapper);
    }

    @Test
    void scheduleTaskReminder_stampsUserTimezoneIntoPayload() throws Exception {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.getSettings(userId)).thenReturn(defaultSettings());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Asia/Yekaterinburg"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(captor.getValue().getPayload(), Map.class);
        assertThat(payload).containsEntry("timezone", "Asia/Yekaterinburg");
        assertThat(captor.getValue().getTelegramChatId()).isEqualTo(12345L);
    }

    @Test
    void scheduleTaskReminder_usesTimezoneFromUserService_notHardcoded() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.getSettings(userId)).thenReturn(defaultSettings());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Europe/Kaliningrad"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(userService).getTimezone(userId);
    }

    @Test
    void scheduleTaskReminder_skipsWhenNoTelegramIdentity() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.empty());

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(scheduledNotificationRepository, never()).save(any());
    }

    @Test
    void cancelTaskNotifications_deletesUnsentByTaskId() {
        notificationService.cancelTaskNotifications(taskId);

        verify(scheduledNotificationRepository).deleteUnsentByTaskId(taskId);
    }

    @Test
    void transferOwnership_setsChatIdFromTargetsTelegram_whenTargetHasOne() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(userService.findExternalId(to, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("999"));

        notificationService.transferOwnership(from, to);

        verify(scheduledNotificationRepository).reassignOwner(from, to, 999L);
        verify(scheduledNotificationRepository, never()).reassignOwnerKeepChatId(any(), any());
    }

    @Test
    void transferOwnership_keepsOldChatId_whenTargetHasNoTelegram() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(userService.findExternalId(to, IdentityProvider.TELEGRAM)).thenReturn(Optional.empty());

        notificationService.transferOwnership(from, to);

        verify(scheduledNotificationRepository).reassignOwnerKeepChatId(from, to);
        verify(scheduledNotificationRepository, never()).reassignOwner(any(), any(), anyLong());
    }

    private UserSettingsDto defaultSettings() {
        return new UserSettingsDto(true, 60, true, "groq", null, "SILENCE", "SILENCE", "Europe/Moscow", "user");
    }
}
