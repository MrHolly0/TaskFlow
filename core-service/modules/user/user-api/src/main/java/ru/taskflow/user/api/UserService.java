package ru.taskflow.user.api;

import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.ZoneId;
import java.util.UUID;

public interface UserService {

    UserDto findOrCreateByIdentity(IdentityProvider provider, String externalId, UserProfile profile);

    UserDto findOrCreateByTelegram(long telegramId, String username, String firstName, String lastName);

    UserDto findById(UUID userId);

    UserSettingsDto getSettings(UUID userId);

    void updateSettings(UUID userId, UpdateSettingsRequest request);

    ZoneId getTimezone(UUID userId);
}