package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.user.infrastructure.persistence.UserJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserRepository;
import ru.taskflow.user.infrastructure.persistence.UserSettingsRepository;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
}
