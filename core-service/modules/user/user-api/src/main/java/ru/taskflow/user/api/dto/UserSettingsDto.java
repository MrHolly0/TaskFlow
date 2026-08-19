package ru.taskflow.user.api.dto;

public record UserSettingsDto(
        boolean notificationsEnabled,
        boolean notifyTelegram,
        boolean notifyEmail,
        int defaultReminderMinutes,
        boolean urgentExtraReminder,
        String preferredLlm,
        Integer autoCleanCompletedDays,
        String voiceInputModeDesktop,
        String voiceInputModeMobile,
        String timezone,
        String displayName
) {}
