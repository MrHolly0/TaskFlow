package ru.taskflow.task.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.infrastructure.persistence.ReminderJpaEntity;
import ru.taskflow.task.infrastructure.persistence.ReminderRepository;
import ru.taskflow.task.infrastructure.persistence.ReminderStatus;
import ru.taskflow.task.infrastructure.persistence.TaskJpaEntity;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.UserSettingsDto;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Планирует и отменяет напоминания через таблицу reminders — она источник
 * истины о том, сколько у задачи напоминаний и когда каждое сработает
 * (Б1), а не deadline задачи, вычисляемый заново при каждом обращении.
 * Срок задачи и время напоминания — разные вещи (обоснование в
 * исследование-напоминания.md): срок можно не указывать вовсе, а
 * напоминание всё равно назначить (Б2).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskReminderService {

    // Совпадает с подписью в настройках («Доп. за 15 мин для срочных») —
    // отдельного поля под этот отступ в UserSettingsDto нет, urgentExtraReminder
    // только включает/выключает саму идею дополнительного напоминания.
    private static final int URGENT_EXTRA_MINUTES = 15;

    private final ReminderRepository reminderRepository;
    private final NotificationService notificationService;
    private final UserService userService;
    private final Clock clock;

    /**
     * Обычное напоминание — за defaultReminderMinutes до срока. Если это
     * время уже прошло, а сам срок ещё нет — напоминаем в момент срока
     * (А4). Если прошёл и сам срок — не напоминаем вовсе. Для приоритета
     * URGENT при включённом urgentExtraReminder — ещё одно, за 15 минут до
     * срока (А6), если оно не совпало с обычным.
     */
    @Transactional
    public void planForDeadline(UUID userId, TaskJpaEntity task) {
        OffsetDateTime deadline = task.getDeadline();
        if (deadline == null) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (deadline.isBefore(now)) {
            log.debug("Дедлайн задачи {} уже прошёл — напоминание не планируется", task.getId());
            return;
        }

        UserSettingsDto settings = userService.getSettings(userId);
        OffsetDateTime fireAt = clampToDeadline(deadline.minusMinutes(settings.defaultReminderMinutes()), deadline, now);
        createReminder(userId, task, fireAt, deadline);

        if (task.getPriority() == TaskPriority.URGENT && settings.urgentExtraReminder()) {
            OffsetDateTime urgentFireAt = clampToDeadline(deadline.minusMinutes(URGENT_EXTRA_MINUTES), deadline, now);
            if (!urgentFireAt.isEqual(fireAt)) {
                createReminder(userId, task, urgentFireAt, deadline);
            }
        }
    }

    /**
     * Б2: напоминание на конкретное время независимо от срока задачи —
     * deadline задачи здесь не читается и не меняется.
     */
    @Transactional
    public void createStandaloneReminder(UUID userId, TaskJpaEntity task, OffsetDateTime fireAt) {
        createReminder(userId, task, fireAt, null);
    }

    /**
     * Единая точка отмены для всех переходов, после которых напоминания
     * задачи перестают быть актуальными: завершение, отмена, удаление,
     * смена дедлайна/названия. Отменяет только ещё не сработавшие
     * напоминания в reminders — саму задачу не трогает: пропуск не
     * помечает её просроченной (Б4).
     */
    @Transactional
    public void cancelForTask(UUID taskId) {
        reminderRepository.cancelPendingByTaskId(taskId);
        notificationService.cancelTaskNotifications(taskId);
    }

    private OffsetDateTime clampToDeadline(OffsetDateTime computedFireAt, OffsetDateTime deadline, OffsetDateTime now) {
        return computedFireAt.isBefore(now) ? deadline : computedFireAt;
    }

    private void createReminder(UUID userId, TaskJpaEntity task, OffsetDateTime fireAt, OffsetDateTime deadlineForDisplay) {
        var reminder = new ReminderJpaEntity();
        reminder.setTask(task);
        reminder.setFireAt(fireAt);
        reminder.setStatus(ReminderStatus.PENDING);
        reminderRepository.save(reminder);
        notificationService.scheduleReminder(userId, task.getId(), task.getTitle(), fireAt, deadlineForDisplay);
    }
}
