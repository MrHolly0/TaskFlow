package ru.taskflow.task.api.exception;

import ru.taskflow.shared.exception.NotFoundException;

import java.util.UUID;

public class ReminderNotFoundException extends NotFoundException {

    public ReminderNotFoundException(UUID reminderId) {
        super("напоминание не найдено: " + reminderId);
    }
}
