package ru.taskflow.user.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "user_settings")
@Getter
@Setter
public class UserSettingsJpaEntity {

    @Id
    private UUID userId;

    @OneToOne
    @MapsId
    @JoinColumn(name = "user_id")
    private UserJpaEntity user;

    @Column(name = "notifications_enabled")
    private boolean notificationsEnabled = true;

    @Column(name = "notify_telegram")
    private boolean notifyTelegram = true;

    // Личный ящик Яндекса, лимит около 500 писем в сутки — по умолчанию
    // выключено для новых пользователей (решение владельца от 20.08.2026).
    // У существующих строк, где уже true, значение сохранится — эта
    // константа управляет только НОВЫМИ записями (see также миграция
    // 023-free-channels-by-default.yaml для DEFAULT самой колонки).
    @Column(name = "notify_email")
    private boolean notifyEmail = false;

    @Column(name = "notify_push")
    private boolean notifyPush = true;

    @Column(name = "default_reminder_minutes")
    private int defaultReminderMinutes = 60;

    @Column(name = "urgent_extra_reminder")
    private boolean urgentExtraReminder = true;

    @Column(name = "preferred_llm", length = 32)
    private String preferredLlm = "groq";

    @Column(name = "auto_clean_completed_days")
    private Integer autoCleanCompletedDays;

    @Column(name = "voice_input_mode_desktop", length = 16)
    private String voiceInputModeDesktop = "SILENCE";

    @Column(name = "voice_input_mode_mobile", length = 16)
    private String voiceInputModeMobile = "SILENCE";
}
