package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextBuilderTest {

    @Mock
    private TaskService taskService;

    @InjectMocks
    private ContextBuilder contextBuilder;

    private final UUID userId = UUID.randomUUID();

    @Test
    void build_assignsSequentialRefs() {
        var first = task(UUID.randomUUID(), "купить молоко", null);
        var second = task(UUID.randomUUID(), "позвонить марку", null);
        when(taskService.findAssistantContext(eq(userId), anyInt())).thenReturn(List.of(first, second));

        var window = contextBuilder.build(userId);

        assertThat(window.refs()).containsOnlyKeys("T1", "T2");
        assertThat(window.resolve("T1")).isEqualTo(first.id());
        assertThat(window.resolve("T2")).isEqualTo(second.id());
    }

    @Test
    void build_rendersTitleAndGroup() {
        var task = task(UUID.randomUUID(), "купить молоко", null);
        when(taskService.findAssistantContext(eq(userId), anyInt())).thenReturn(List.of(task));

        var window = contextBuilder.build(userId);

        assertThat(window.rendered()).contains("T1").contains("купить молоко").contains("Покупки");
    }

    @Test
    void build_marksTaskWithoutDeadline() {
        var task = task(UUID.randomUUID(), "купить молоко", null);
        when(taskService.findAssistantContext(eq(userId), anyInt())).thenReturn(List.of(task));

        var window = contextBuilder.build(userId);

        assertThat(window.rendered()).contains("без срока");
    }

    @Test
    void build_marksOverdueTask() {
        var overdue = task(UUID.randomUUID(), "сдать отчёт", OffsetDateTime.now().minusDays(3));
        when(taskService.findAssistantContext(eq(userId), anyInt())).thenReturn(List.of(overdue));

        var window = contextBuilder.build(userId);

        assertThat(window.rendered()).contains("ПРОСРОЧЕНО");
    }

    @Test
    void build_returnsEmptyWindowWhenNoTasks() {
        when(taskService.findAssistantContext(eq(userId), anyInt())).thenReturn(List.of());

        var window = contextBuilder.build(userId);

        assertThat(window.refs()).isEmpty();
        assertThat(window.rendered()).isEmpty();
    }

    @Test
    void resolve_returnsNullForUnknownRef() {
        when(taskService.findAssistantContext(eq(userId), anyInt())).thenReturn(List.of());

        var window = contextBuilder.build(userId);

        assertThat(window.resolve("T99")).isNull();
    }

    private TaskResponse task(UUID id, String title, OffsetDateTime deadline) {
        return new TaskResponse(
                id, title, null, TaskPriority.MEDIUM, TaskStatus.TODO, deadline,
                null, false, TaskSource.MANUAL, UUID.randomUUID(), "Покупки",
                List.of(), OffsetDateTime.now(), OffsetDateTime.now(), null
        );
    }
}
