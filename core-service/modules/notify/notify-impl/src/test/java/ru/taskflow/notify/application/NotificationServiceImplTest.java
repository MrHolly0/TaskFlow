package ru.taskflow.notify.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.notify.api.NotificationChannel;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionRepository;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.ScheduledNotificationRepository;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private ScheduledNotificationRepository scheduledNotificationRepository;
    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;
    @Mock
    private UserService userService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationServiceImpl notificationService;

    private final UUID userId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(
                scheduledNotificationRepository, pushSubscriptionRepository, userService, objectMapper);
        lenient().when(userService.getSettings(userId)).thenReturn(defaultSettings());
        lenient().when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Europe/Moscow"));
        lenient().when(pushSubscriptionRepository.findByUserId(any())).thenReturn(List.of());
    }

    @Test
    void scheduleTaskReminder_createsOneRowPerChannelWhenBothIdentitiesExist() {
        // Строка на канал, не веер в одной: отказ одного не должен помешать другому.
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.of("user@example.com"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository, times(2)).save(captor.capture());

        List<ScheduledNotificationJpaEntity> saved = captor.getAllValues();
        assertThat(saved).extracting(ScheduledNotificationJpaEntity::getChannel)
                .containsExactlyInAnyOrder(NotificationChannel.TELEGRAM, NotificationChannel.EMAIL);
        assertThat(saved).filteredOn(n -> n.getChannel() == NotificationChannel.TELEGRAM)
                .extracting(ScheduledNotificationJpaEntity::getDestination).containsExactly("12345");
        assertThat(saved).filteredOn(n -> n.getChannel() == NotificationChannel.EMAIL)
                .extracting(ScheduledNotificationJpaEntity::getDestination).containsExactly("user@example.com");
    }

    @Test
    void scheduleTaskReminder_createsOnlyTelegramRowWhenNoEmailIdentity() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.TELEGRAM);
    }

    @Test
    void scheduleTaskReminder_stampsUserTimezoneIntoPayload() throws Exception {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Asia/Yekaterinburg"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(captor.getValue().getPayload(), Map.class);
        assertThat(payload).containsEntry("timezone", "Asia/Yekaterinburg");
        assertThat(captor.getValue().getDestination()).isEqualTo("12345");
    }

    @Test
    void scheduleTaskReminder_usesTimezoneFromUserService_notHardcoded() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());
        when(userService.getTimezone(userId)).thenReturn(ZoneId.of("Europe/Kaliningrad"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(userService).getTimezone(userId);
    }

    @Test
    void scheduleTaskReminder_skipsWhenNoIdentityAtAll() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.empty());
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(scheduledNotificationRepository, never()).save(any());
        verify(userService, never()).getTimezone(any());
    }

    @Test
    void cancelTaskNotifications_deletesUnsentByTaskId() {
        notificationService.cancelTaskNotifications(taskId);

        verify(scheduledNotificationRepository).deleteUnsentByTaskId(taskId);
    }

    @Test
    void transferOwnership_passesTargetsIdentitiesForBothChannels() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(userService.findExternalId(to, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("999"));
        when(userService.findExternalId(to, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.transferOwnership(from, to);

        verify(scheduledNotificationRepository).reassignOwner(from, to, "999", null);
    }

    @Test
    void transferOwnership_passesNullForBothChannels_whenTargetHasNeitherIdentity() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(userService.findExternalId(to, IdentityProvider.TELEGRAM)).thenReturn(Optional.empty());
        when(userService.findExternalId(to, IdentityProvider.EMAIL)).thenReturn(Optional.empty());

        notificationService.transferOwnership(from, to);

        verify(scheduledNotificationRepository).reassignOwner(from, to, null, null);
    }

    @Test
    void scheduleTaskReminder_skipsEverythingWhenMasterSwitchIsOff() {
        when(userService.getSettings(userId)).thenReturn(settings(false, true, true));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(scheduledNotificationRepository, never()).save(any());
        verify(userService, never()).findExternalId(any(), any());
    }

    @Test
    void scheduleTaskReminder_doesNotCheckIdentityForDisabledChannel() {
        // Выключенный переключатель не должен даже смотреть на идентичность —
        // при включённом он бы её нашёл, поэтому это разница именно от тумблера.
        when(userService.getSettings(userId)).thenReturn(settings(true, false, true));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.of("user@example.com"));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(userService, never()).findExternalId(userId, IdentityProvider.TELEGRAM);
        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void scheduleTaskReminder_skipsChannelWithIdentityButToggleOff() {
        when(userService.getSettings(userId)).thenReturn(settings(true, false, false));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        verify(scheduledNotificationRepository, never()).save(any());
    }

    @Test
    void scheduleTaskReminder_createsOneRowPerPushSubscription() {
        // Один пользователь — рабочий компьютер и телефон: обе подписки должны
        // получить своё напоминание, отказ одной не мешает другой.
        UUID subscriptionA = UUID.randomUUID();
        UUID subscriptionB = UUID.randomUUID();
        when(userService.findExternalId(any(), any())).thenReturn(Optional.empty());
        when(pushSubscriptionRepository.findByUserId(userId)).thenReturn(List.of(
                subscription(subscriptionA), subscription(subscriptionB)));

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ScheduledNotificationJpaEntity::getChannel)
                .containsExactly(NotificationChannel.WEB_PUSH, NotificationChannel.WEB_PUSH);
        assertThat(captor.getAllValues()).extracting(ScheduledNotificationJpaEntity::getDestination)
                .containsExactlyInAnyOrder(subscriptionA.toString(), subscriptionB.toString());
    }

    @Test
    void scheduleTaskReminder_noPushSubscriptions_noWebPushRow() {
        when(userService.findExternalId(userId, IdentityProvider.TELEGRAM)).thenReturn(Optional.of("12345"));
        when(userService.findExternalId(userId, IdentityProvider.EMAIL)).thenReturn(Optional.empty());
        when(pushSubscriptionRepository.findByUserId(userId)).thenReturn(List.of());

        notificationService.scheduleTaskReminder(userId, taskId, "задача", OffsetDateTime.now().plusDays(1));

        ArgumentCaptor<ScheduledNotificationJpaEntity> captor = ArgumentCaptor.forClass(ScheduledNotificationJpaEntity.class);
        verify(scheduledNotificationRepository).save(captor.capture());
        assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.TELEGRAM);
    }

    private PushSubscriptionJpaEntity subscription(UUID id) {
        var entity = new PushSubscriptionJpaEntity();
        entity.setId(id);
        return entity;
    }

    private UserSettingsDto defaultSettings() {
        return settings(true, true, true);
    }

    private UserSettingsDto settings(boolean notificationsEnabled, boolean notifyTelegram, boolean notifyEmail) {
        return new UserSettingsDto(notificationsEnabled, notifyTelegram, notifyEmail,
                60, true, "groq", null, "SILENCE", "SILENCE", "Europe/Moscow", "user");
    }
}
