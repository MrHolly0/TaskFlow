package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;
import ru.taskflow.user.infrastructure.persistence.UserIdentityJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserIdentityRepository;
import ru.taskflow.user.infrastructure.persistence.UserJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserRepository;
import ru.taskflow.user.infrastructure.persistence.UserSettingsJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserSettingsRepository;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSettingsRepository settingsRepository;

    @Mock
    private UserIdentityRepository identityRepository;

    @Test
    void getTimezone_returnsStoredZone() {
        UUID userId = UUID.randomUUID();
        UserJpaEntity entity = new UserJpaEntity();
        entity.setTimezone("Asia/Yekaterinburg");
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));
        UserServiceImpl service = newService();

        ZoneId zone = service.getTimezone(userId);

        assertThat(zone).isEqualTo(ZoneId.of("Asia/Yekaterinburg"));
    }

    @Test
    void getTimezone_throwsWhenUserMissing() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.getTimezone(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getSettings_defaultsVoiceModesToSilenceWhenNoneStored() {
        UUID userId = UUID.randomUUID();
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        UserSettingsDto settings = service.getSettings(userId);

        assertThat(settings.voiceInputModeDesktop()).isEqualTo("SILENCE");
        assertThat(settings.voiceInputModeMobile()).isEqualTo("SILENCE");
    }

    @Test
    void updateSettings_updatesVoiceModes() {
        UUID userId = UUID.randomUUID();
        var entity = new UserSettingsJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, null, null, "TOGGLE", "HOLD", null);
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
        UserServiceImpl service = newService();

        service.updateSettings(userId, request);

        ArgumentCaptor<UserSettingsJpaEntity> captor = ArgumentCaptor.forClass(UserSettingsJpaEntity.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().getVoiceInputModeDesktop()).isEqualTo("TOGGLE");
        assertThat(captor.getValue().getVoiceInputModeMobile()).isEqualTo("HOLD");
    }

    @Test
    void getSettings_returnsStoredTimezone() {
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        user.setTimezone("Asia/Yekaterinburg");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        UserSettingsDto settings = service.getSettings(userId);

        assertThat(settings.timezone()).isEqualTo("Asia/Yekaterinburg");
    }

    @Test
    void updateSettings_savesValidTimezone() {
        UUID userId = UUID.randomUUID();
        var settingsEntity = new UserSettingsJpaEntity();
        var user = new UserJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, "Asia/Yekaterinburg");
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(settingsEntity));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        UserServiceImpl service = newService();

        service.updateSettings(userId, request);

        ArgumentCaptor<UserJpaEntity> captor = ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTimezone()).isEqualTo("Asia/Yekaterinburg");
    }

    @Test
    void updateSettings_rejectsUnknownTimezone() {
        UUID userId = UUID.randomUUID();
        var request = new UpdateSettingsRequest(null, null, null, null, null, null, null, "Mars/Colony");
        UserServiceImpl service = newService();

        assertThatThrownBy(() -> service.updateSettings(userId, request))
                .isInstanceOf(ValidationException.class);

        verify(userRepository, never()).save(any());
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void findOrCreateByIdentity_findsExistingUserByIdentity() {
        UUID userId = UUID.randomUUID();
        var user = new UserJpaEntity();
        user.setId(userId);
        user.setUsername("someone");
        var identity = new UserIdentityJpaEntity();
        identity.setUser(user);
        identity.setProvider(IdentityProvider.TELEGRAM);
        identity.setExternalId("42");
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42"))
                .thenReturn(Optional.of(identity));
        UserServiceImpl service = newService();

        var dto = service.findOrCreateByIdentity(IdentityProvider.TELEGRAM, "42",
                new UserProfile("someone", "First", "Last", null));

        assertThat(dto.id()).isEqualTo(userId);
        verify(userRepository, never()).save(any());
    }

    @Test
    void findOrCreateByIdentity_createsUserAndIdentityWhenMissing() {
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(UserJpaEntity.class))).thenAnswer(inv -> {
            UserJpaEntity saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        UserServiceImpl service = newService();

        var dto = service.findOrCreateByIdentity(IdentityProvider.TELEGRAM, "42",
                new UserProfile("someone", "First", "Last", null));

        assertThat(dto.id()).isNotNull();
        ArgumentCaptor<UserIdentityJpaEntity> captor = ArgumentCaptor.forClass(UserIdentityJpaEntity.class);
        verify(identityRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo(IdentityProvider.TELEGRAM);
        assertThat(captor.getValue().getExternalId()).isEqualTo("42");
    }

    @Test
    void findOrCreateByTelegram_delegatesToFindOrCreateByIdentity() {
        when(identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42"))
                .thenReturn(Optional.empty());
        when(userRepository.save(any(UserJpaEntity.class))).thenAnswer(inv -> {
            UserJpaEntity saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });
        UserServiceImpl service = newService();

        service.findOrCreateByTelegram(42L, "someone", "First", "Last");

        verify(identityRepository).findByProviderAndExternalId(IdentityProvider.TELEGRAM, "42");
    }

    @Test
    void findExternalId_returnsExternalIdWhenIdentityExists() {
        UUID userId = UUID.randomUUID();
        var identity = new UserIdentityJpaEntity();
        identity.setExternalId("12345");
        when(identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.TELEGRAM))
                .thenReturn(Optional.of(identity));
        UserServiceImpl service = newService();

        var externalId = service.findExternalId(userId, IdentityProvider.TELEGRAM);

        assertThat(externalId).contains("12345");
    }

    @Test
    void findExternalId_returnsEmptyWhenIdentityMissing() {
        UUID userId = UUID.randomUUID();
        when(identityRepository.findByUser_IdAndProvider(userId, IdentityProvider.TELEGRAM))
                .thenReturn(Optional.empty());
        UserServiceImpl service = newService();

        var externalId = service.findExternalId(userId, IdentityProvider.TELEGRAM);

        assertThat(externalId).isEmpty();
    }

    private UserServiceImpl newService() {
        return new UserServiceImpl(userRepository, settingsRepository, identityRepository);
    }
}
