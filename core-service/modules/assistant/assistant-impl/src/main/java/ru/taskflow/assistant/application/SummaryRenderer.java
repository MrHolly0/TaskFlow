package ru.taskflow.assistant.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class SummaryRenderer {

    private static final int MAX_LENGTH = 256;
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public String render(AssistantActionType type, String taskTitle, Map<String, Object> payload) {
        String body = switch (type) {
            case CREATE -> "Создать — " + stringOrDefault(payload.get("title"), "без названия")
                    + createDeadlineSuffix(payload.get("deadline"));
            case COMPLETE -> "Закрыть — " + stringOrDefault(taskTitle, "задача");
            case CANCEL -> "Отменить — " + stringOrDefault(taskTitle, "задача");
            case RESCHEDULE -> "Перенести — " + stringOrDefault(taskTitle, "задача")
                    + " → " + formatDeadline(payload.get("new_deadline"));
            case UPDATE -> "Изменить — " + stringOrDefault(taskTitle, "задача")
                    + " (" + changedFields(payload) + ")";
        };
        return truncate(body);
    }

    private String changedFields(Map<String, Object> payload) {
        List<String> names = new ArrayList<>();
        if (payload.containsKey("title")) names.add("название");
        if (payload.containsKey("description")) names.add("описание");
        if (payload.containsKey("priority")) names.add("приоритет");
        if (payload.containsKey("group")) names.add("группа");
        return names.isEmpty() ? "без изменений" : String.join(", ", names);
    }

    // В отличие от formatDeadline (используется и для RESCHEDULE, где срок
    // обязателен) — у CREATE срок необязателен, и «без срока» в сводке
    // создания только зашумит: подтверждение перед применением защищает
    // ровно настолько, насколько сводка показывает то, что подтверждают,
    // так что здесь дописываем срок, только если он реально есть.
    private String createDeadlineSuffix(Object deadline) {
        if (deadline == null) {
            return "";
        }
        return " · до " + formatDeadline(deadline);
    }

    private String formatDeadline(Object raw) {
        if (raw == null) {
            return "без срока";
        }
        try {
            return OffsetDateTime.parse(raw.toString()).format(DEADLINE_FORMAT);
        } catch (Exception e) {
            log.debug("Не удалось разобрать срок: {}", raw);
            return raw.toString();
        }
    }

    private String stringOrDefault(Object value, String fallback) {
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        return value.toString();
    }

    private String truncate(String value) {
        if (value.length() <= MAX_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LENGTH - 1) + "…";
    }
}
