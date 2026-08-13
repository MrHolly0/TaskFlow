package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSettingsRepository settingsRepository;

    @Test
    void getTimezone_returnsStoredZone() {
        UUID userId = UUID.randomUUID();
        UserJpaEntity entity = new UserJpaEntity();
        entity.setTimezone("Asia/Yekaterinburg");
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));
        UserServiceImpl service = new UserServiceImpl(userRepository, settingsRepository);

        ZoneId zone = service.getTimezone(userId);

        assertThat(zone).isEqualTo(ZoneId.of("Asia/Yekaterinburg"));
    }

    @Test
    void getTimezone_throwsWhenUserMissing() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = new UserServiceImpl(userRepository, settingsRepository);

        assertThatThrownBy(() -> service.getTimezone(userId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getSettings_defaultsVoiceModesToSilenceWhenNoneStored() {
        UUID userId = UUID.randomUUID();
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        UserServiceImpl service = new UserServiceImpl(userRepository, settingsRepository);

        UserSettingsDto settings = service.getSettings(userId);

        assertThat(settings.voiceInputModeDesktop()).isEqualTo("SILENCE");
        assertThat(settings.voiceInputModeMobile()).isEqualTo("SILENCE");
    }

    @Test
    void updateSettings_updatesVoiceModes() {
        UUID userId = UUID.randomUUID();
        var entity = new UserSettingsJpaEntity();
        var request = new UpdateSettingsRequest(null, null, null, null, null, "TOGGLE", "HOLD");
        when(settingsRepository.findByUserId(userId)).thenReturn(Optional.of(entity));
        UserServiceImpl service = new UserServiceImpl(userRepository, settingsRepository);

        service.updateSettings(userId, request);

        ArgumentCaptor<UserSettingsJpaEntity> captor = ArgumentCaptor.forClass(UserSettingsJpaEntity.class);
        verify(settingsRepository).save(captor.capture());
        assertThat(captor.getValue().getVoiceInputModeDesktop()).isEqualTo("TOGGLE");
        assertThat(captor.getValue().getVoiceInputModeMobile()).isEqualTo("HOLD");
    }
}
