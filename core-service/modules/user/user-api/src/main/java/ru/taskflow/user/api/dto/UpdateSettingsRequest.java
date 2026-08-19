package ru.taskflow.user.api.dto;

public record UpdateSettingsRequest(
        Boolean notificationsEnabled,
        Boolean notifyTelegram,
        Boolean notifyEmail,
        Integer defaultReminderMinutes,
        Boolean urgentExtraReminder,
        String preferredLlm,
        Integer autoCleanCompletedDays,
        String voiceInputModeDesktop,
        String voiceInputModeMobile,
        String timezone,
        String displayName
) {}
