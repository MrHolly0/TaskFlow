package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskResponse;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ContextBuilder {

    public static final int WINDOW_SIZE = 80;
    private static final String REF_PREFIX = "T";
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final TaskService taskService;

    public TaskContextWindow build(UUID userId) {
        List<TaskResponse> tasks = taskService.findAssistantContext(userId, WINDOW_SIZE);
        Map<String, UUID> refs = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        StringBuilder rendered = new StringBuilder();

        for (int i = 0; i < tasks.size(); i++) {
            TaskResponse task = tasks.get(i);
            String ref = REF_PREFIX + (i + 1);
            refs.put(ref, task.id());
            titles.put(ref, task.title());
            if (!rendered.isEmpty()) {
                rendered.append('\n');
            }
            rendered.append(renderLine(ref, task));
        }

        return new TaskContextWindow(rendered.toString(), Map.copyOf(refs), Map.copyOf(titles));
    }

    private String renderLine(String ref, TaskResponse task) {
        return "%s · %s · %s · %s · %s".formatted(
                ref,
                task.title(),
                task.groupName() != null ? task.groupName() : "без группы",
                renderDeadline(task.deadline()),
                task.priority()
        );
    }

    private String renderDeadline(OffsetDateTime deadline) {
        if (deadline == null) {
            return "без срока";
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (deadline.isBefore(now)) {
            long days = Duration.between(deadline, now).toDays();
            return days > 0 ? "ПРОСРОЧЕНО " + days + " дн" : "ПРОСРОЧЕНО сегодня";
        }
        return "до " + deadline.format(DEADLINE_FORMAT);
    }
}
