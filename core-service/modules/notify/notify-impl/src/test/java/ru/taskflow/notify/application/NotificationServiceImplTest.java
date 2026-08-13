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
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.infrastructure.persistence.UserJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserRepository;
import ru.taskflow.user.infrastructure.persistence.UserSettingsRepository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private ScheduledNotificationRepository scheduledNotificationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserSettingsRepository userSettingsRepository;
    @Mock
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationServiceImpl notificationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(
                scheduledNotificationRepository, userRepository, userSettingsRepository, userService, objectMapper);
    }

    @Test
    void scheduleTaskReminder_stampsUserTimezoneIntoPayload() throws Exception {
        var user = new UserJpaEntity();
        user.setTelegramId(12345L);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Asia/Yekaterinburg"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(captor.getValue().getPayload(), Map.class);
        assertThat(payload).containsEntry("timezone", "Asia/Yekaterinburg");
    }

    @Test
    void scheduleTaskReminder_usesTimezoneFromUserService_notHardcoded() {
        var user = new UserJpaEntity();
        user.setTelegramId(12345L);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Europe/Kaliningrad"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(userService).getTimezone(userId);
    }

    @Test
    void cancelTaskNotifications_deletesUnsentByTaskId() {
        notificationService.cancelTaskNotifications(taskId);

        verify(scheduledNotificationRepository).deleteUnsentByTaskId(taskId);
    }
}
