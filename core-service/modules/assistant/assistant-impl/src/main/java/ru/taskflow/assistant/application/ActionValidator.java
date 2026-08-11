package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.TaskResponse;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ActionValidator {

    private final TaskService taskService;

    public record ValidationResult(boolean valid, String error, UUID targetTaskId) {

        static ValidationResult ok(UUID targetTaskId) {
            return new ValidationResult(true, null, targetTaskId);
        }

        static ValidationResult fail(String error) {
            return new ValidationResult(false, error, null);
        }
    }

    public ValidationResult validate(AssistantActionType type,
                                     Map<String, Object> args, TaskContextWindow window) {
        if (type == AssistantActionType.CREATE) {
            return validateCreate(args);
        }

        Object rawRef = args.get("task_ref");
        if (rawRef == null || rawRef.toString().isBlank()) {
            return ValidationResult.fail("не указан task_ref");
        }
        String ref = rawRef.toString().trim();
        UUID taskId = window.resolve(ref);
        if (taskId == null) {
            return ValidationResult.fail("неизвестный ярлык задачи: " + ref);
        }

        return switch (type) {
            case RESCHEDULE -> validateReschedule(args, taskId);
            case UPDATE -> validateUpdate(args, taskId);
            case COMPLETE, CANCEL -> ValidationResult.ok(taskId);
            case CREATE -> throw new IllegalStateException("CREATE обработан выше");
        };
    }

    public ValidationResult revalidateForApply(UUID userId, AssistantActionType type, UUID targetTaskId) {
        if (type == AssistantActionType.CREATE) {
            return ValidationResult.ok(null);
        }
        TaskResponse task;
        try {
            task = taskService.findById(userId, targetTaskId);
        } catch (RuntimeException e) {
            return ValidationResult.fail("задача не найдена или недоступна");
        }
        if (task.status() == TaskStatus.DONE && type == AssistantActionType.COMPLETE) {
            return ValidationResult.fail("задача уже закрыта");
        }
        if (task.status() == TaskStatus.CANCELLED) {
            return ValidationResult.fail("задача уже отменена");
        }
        return ValidationResult.ok(targetTaskId);
    }

    private ValidationResult validateCreate(Map<String, Object> args) {
        Object title = args.get("title");
        if (title == null || title.toString().isBlank()) {
            return ValidationResult.fail("не указан title");
        }
        if (args.containsKey("priority") && !isKnownPriority(args.get("priority"))) {
            return ValidationResult.fail("неизвестный приоритет: " + args.get("priority"));
        }
        if (args.containsKey("deadline") && !isParseableDeadline(args.get("deadline"))) {
            return ValidationResult.fail("не удалось разобрать срок: " + args.get("deadline"));
        }
        return ValidationResult.ok(null);
    }

    private ValidationResult validateReschedule(Map<String, Object> args, UUID taskId) {
        Object deadline = args.get("new_deadline");
        if (deadline == null || deadline.toString().isBlank()) {
            return ValidationResult.fail("не указан new_deadline");
        }
        if (!isParseableDeadline(deadline)) {
            return ValidationResult.fail("не удалось разобрать срок: " + deadline);
        }
        return ValidationResult.ok(taskId);
    }

    private ValidationResult validateUpdate(Map<String, Object> args, UUID taskId) {
        if (args.containsKey("priority") && !isKnownPriority(args.get("priority"))) {
            return ValidationResult.fail("неизвестный приоритет: " + args.get("priority"));
        }
        return ValidationResult.ok(taskId);
    }

    private boolean isKnownPriority(Object raw) {
        if (raw == null) {
            return false;
        }
        try {
            TaskPriority.valueOf(raw.toString().trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isParseableDeadline(Object raw) {
        if (raw == null) {
            return false;
        }
        try {
            OffsetDateTime.parse(raw.toString().trim());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
