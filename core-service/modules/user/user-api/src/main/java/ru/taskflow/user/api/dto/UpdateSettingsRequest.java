package ru.taskflow.user.api.dto;

public record UpdateSettingsRequest(
        Boolean notificationsEnabled,
        Integer defaultReminderMinutes,
        Boolean urgentExtraReminder,
        String preferredLlm,
        Integer autoCleanCompletedDays,
        String voiceInputModeDesktop,
        String voiceInputModeMobile
) {}
