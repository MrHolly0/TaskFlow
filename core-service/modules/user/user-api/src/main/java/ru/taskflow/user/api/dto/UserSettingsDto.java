package ru.taskflow.user.api.dto;

public record UserSettingsDto(
        boolean notificationsEnabled,
        int defaultReminderMinutes,
        boolean urgentExtraReminder,
        String preferredLlm,
        Integer autoCleanCompletedDays
) {}
