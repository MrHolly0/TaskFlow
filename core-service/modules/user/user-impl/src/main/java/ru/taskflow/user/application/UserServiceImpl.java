package ru.taskflow.user.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UpdateSettingsRequest;
import ru.taskflow.user.api.dto.UserSettingsDto;
import ru.taskflow.user.infrastructure.persistence.UserJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserRepository;
import ru.taskflow.user.infrastructure.persistence.UserSettingsJpaEntity;
import ru.taskflow.user.infrastructure.persistence.UserSettingsRepository;

import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserSettingsRepository settingsRepository;

    @Override
    @Transactional
    public UserDto findOrCreateByTelegram(long telegramId, String username, String firstName, String lastName) {
        return userRepository.findByTelegramId(telegramId)
                .map(this::toDto)
                .orElseGet(() -> {
                    var entity = new UserJpaEntity();
                    entity.setTelegramId(telegramId);
                    entity.setUsername(username);
                    entity.setFirstName(firstName);
                    entity.setLastName(lastName);
                    return toDto(userRepository.save(entity));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto findById(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toDto)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    @Override
    @Transactional(readOnly = true)
    public UserSettingsDto getSettings(UUID userId) {
        return settingsRepository.findByUserId(userId)
                .map(this::toSettingsDto)
                .orElseGet(this::defaultSettings);
    }

    @Override
    @Transactional(readOnly = true)
    public ZoneId getTimezone(UUID userId) {
        return userRepository.findById(userId)
                .map(e -> ZoneId.of(e.getTimezone()))
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }

    @Override
    @Transactional
    public void updateSettings(UUID userId, UpdateSettingsRequest request) {
        var settings = settingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));

        if (request.notificationsEnabled() != null) settings.setNotificationsEnabled(request.notificationsEnabled());
        if (request.defaultReminderMinutes() != null) settings.setDefaultReminderMinutes(request.defaultReminderMinutes());
        if (request.urgentExtraReminder() != null) settings.setUrgentExtraReminder(request.urgentExtraReminder());
        if (request.preferredLlm() != null) settings.setPreferredLlm(request.preferredLlm());
        if (request.autoCleanCompletedDays() != null) settings.setAutoCleanCompletedDays(request.autoCleanCompletedDays());
        if (request.voiceInputModeDesktop() != null) settings.setVoiceInputModeDesktop(request.voiceInputModeDesktop());
        if (request.voiceInputModeMobile() != null) settings.setVoiceInputModeMobile(request.voiceInputModeMobile());

        settingsRepository.save(settings);
    }

    private UserSettingsJpaEntity createDefaultSettings(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        var settings = new UserSettingsJpaEntity();
        settings.setUser(user);
        return settings;
    }

    private UserSettingsDto toSettingsDto(UserSettingsJpaEntity e) {
        return new UserSettingsDto(
                e.isNotificationsEnabled(),
                e.getDefaultReminderMinutes(),
                e.isUrgentExtraReminder(),
                e.getPreferredLlm(),
                e.getAutoCleanCompletedDays(),
                e.getVoiceInputModeDesktop(),
                e.getVoiceInputModeMobile()
        );
    }

    private UserSettingsDto defaultSettings() {
        return new UserSettingsDto(true, 60, true, "groq", null, "SILENCE", "SILENCE");
    }

    private UserDto toDto(UserJpaEntity e) {
        return new UserDto(e.getId(), e.getTelegramId(), e.getUsername(), e.getFirstName(), e.getLastName());
    }
}
