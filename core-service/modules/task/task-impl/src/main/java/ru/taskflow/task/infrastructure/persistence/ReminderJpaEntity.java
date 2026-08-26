package ru.taskflow.task.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Напоминание независимо от срока задачи: у задачи их может быть ноль, одно
 * или несколько, каждое со своим fireAt и своим статусом (Б1). fireAt —
 * абсолютное время, посчитанное один раз при планировании (TaskReminderService),
 * а не выведенное заново из deadline при каждом обращении — так напоминание
 * может существовать и на задаче без срока (Б2).
 */
@Entity
@Table(name = "reminders")
@Getter
@Setter
public class ReminderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    private TaskJpaEntity task;

    @Column(name = "fire_at", nullable = false)
    private OffsetDateTime fireAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
