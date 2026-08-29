package ru.taskflow.assistant.api.dto;

import java.util.UUID;

/**
 * taskId — задача, которую затронуло действие (создана или изменена),
 * null при отказе (success=false) или для типов действий, где это
 * неприменимо. Появилось не ради контура ассистента — модель этого поля
 * не видит и не заполняет: интерфейсу после подтверждения нужно на что-то
 * сослаться (например, включить настойчивость только что созданной задаче
 * без похода за её id отдельным запросом).
 */
public record ActionOutcome(
        int ordinal,
        String summary,
        boolean success,
        String error,
        UUID taskId
) {
    // Совместимость: до появления taskId вызывающая сторона не знала,
    // какую задачу затронуло действие.
    public ActionOutcome(int ordinal, String summary, boolean success, String error) {
        this(ordinal, summary, success, error, null);
    }
}
