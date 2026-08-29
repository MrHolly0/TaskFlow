package ru.taskflow.task.infrastructure.persistence;

/**
 * PENDING — ещё должно сработать. CANCELLED — задача завершена, отменена,
 * получила новый срок раньше, чем напоминание успело сработать, или у неё
 * сняли флаг настойчивости (см. TaskReminderService.cancelForTask/
 * cancelPersistentChain). SNOOZED — конкретный повтор настойчивой цепочки
 * человек отложил на выбранный срок (следующий повтор — новая строка).
 * DISMISSED — время повтора прошло без решения человека: сам погас
 * (следующий шаг цепочки создан автоматически) либо цепочка на нём
 * закончилась (Б3, затухание).
 */
public enum ReminderStatus {
    PENDING, CANCELLED, SNOOZED, DISMISSED
}
