package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.TaskResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID existingTaskId = UUID.randomUUID();
    private final UUID foundTaskId = UUID.randomUUID();
    private final ZoneOffset zone = ZoneOffset.UTC;
    private final Instant now = Instant.parse("2026-08-12T10:00:00Z");

    private final ContextBuilder contextBuilder = mock(ContextBuilder.class);
    private final NlpGatewayService gateway = mock(NlpGatewayService.class);
    private final TaskService taskService = mock(TaskService.class);

    private final AssistantPromptBuilder promptBuilder = new AssistantPromptBuilder();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ToolCallParser toolCallParser = new ToolCallParser(
            toolRegistry, new ActionValidator(taskService), new SummaryRenderer(), new ObjectMapper());
    private final DuplicateGuard duplicateGuard = new DuplicateGuard();

    private TaskContextWindow window() {
        return new TaskContextWindow(
                "T1 · купить молоко",
                Map.of("T1", existingTaskId),
                Map.of("T1", "купить молоко"));
    }

    private AgentLoop loop(Clock clock) {
        return new AgentLoop(contextBuilder, promptBuilder, toolRegistry, gateway,
                toolCallParser, duplicateGuard, taskService, clock);
    }

    private AgentLoop loopWithFixedClock() {
        return loop(Clock.fixed(now, zone));
    }

    private LlmToolCall completeTaskCall() {
        return new LlmToolCall("call-1", "complete_task", "{\"task_ref\":\"T1\"}");
    }

    private LlmToolCall createTaskCall(String title) {
        return new LlmToolCall("call-create", "create_task", "{\"title\":\"" + title + "\"}");
    }

    private LlmToolCall searchCall(String query) {
        return new LlmToolCall("call-search", "search_tasks", "{\"query\":\"" + query + "\"}");
    }

    private LlmToolCall askUserCall() {
        return new LlmToolCall("call-ask", "ask_user",
                "{\"question\":\"какую задачу закрыть?\",\"options\":[\"первую\",\"вторую\"]}");
    }

    private LlmToolResponse toolResponse(List<LlmToolCall> calls, String text) {
        return new LlmToolResponse(calls, text, 10, 5, false);
    }

    private TaskResponse taskResponse(UUID id, String title) {
        return new TaskResponse(id, title, null, TaskPriority.MEDIUM, TaskStatus.TODO,
                null, null, false, TaskSource.MANUAL, null, null, List.of(),
                OffsetDateTime.now(), OffsetDateTime.now(), null);
    }

    @Test
    void run_returnsActionsFromSinglePass() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "закрой молоко", zone);

        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.COMPLETE);
        assertThat(outcome.actions().getFirst().targetTaskId()).isEqualTo(existingTaskId);
        assertThat(outcome.passes()).isEqualTo(1);
        assertThat(outcome.llmFailed()).isFalse();
        verify(gateway, times(1)).callWithTools(any());
    }

    @Test
    void run_makesSecondPassAfterSearch() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("аптека")), null),
                toolResponse(List.of(), "готово"));
        when(taskService.search(userId, "аптека", false, 20))
                .thenReturn(List.of(taskResponse(foundTaskId, "Купить лекарство в аптеке")));

        loopWithFixedClock().run(userId, "найди задачу про аптеку", zone);

        var captor = org.mockito.ArgumentCaptor.forClass(LlmToolRequest.class);
        verify(gateway, times(2)).callWithTools(captor.capture());

        var secondRequest = captor.getAllValues().get(1);
        assertThat(secondRequest.messages()).hasSize(4);
        assertThat(secondRequest.messages().get(2).role()).isEqualTo("assistant");
        assertThat(secondRequest.messages().get(3).role()).isEqualTo("tool");
        assertThat(secondRequest.messages().get(3).toolCallId()).isEqualTo("call-search");
        assertThat(secondRequest.messages().get(3).content()).contains("Купить лекарство в аптеке");
    }

    @Test
    void run_extendsWindowWithSearchResults() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("аптека")), null),
                toolResponse(List.of(), "готово"));
        when(taskService.search(userId, "аптека", false, 20))
                .thenReturn(List.of(taskResponse(foundTaskId, "Купить лекарство в аптеке")));

        var outcome = loopWithFixedClock().run(userId, "найди задачу про аптеку", zone);

        assertThat(outcome.window().resolve("T1")).isEqualTo(existingTaskId);
        assertThat(outcome.window().resolve("T2")).isEqualTo(foundTaskId);
    }

    @Test
    void run_stopsAtTwoPasses() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("аптека")), null),
                toolResponse(List.of(searchCall("ещё раз")), null));
        when(taskService.search(userId, "аптека", false, 20))
                .thenReturn(List.of(taskResponse(foundTaskId, "Купить лекарство в аптеке")));

        var outcome = loopWithFixedClock().run(userId, "найди задачу", zone);

        verify(gateway, times(2)).callWithTools(any());
        assertThat(outcome.passes()).isEqualTo(2);
    }

    @Test
    void run_stopsOnClarification() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(askUserCall()), null));

        var outcome = loopWithFixedClock().run(userId, "закрой задачу", zone);

        verify(gateway, times(1)).callWithTools(any());
        assertThat(outcome.clarification()).isEqualTo("какую задачу закрыть?");
        assertThat(outcome.clarificationOptions()).containsExactly("первую", "вторую");
        assertThat(outcome.passes()).isEqualTo(1);
    }

    @Test
    void run_returnsTextWhenNoToolCalls() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(), "Не понял, уточните пожалуйста."));

        var outcome = loopWithFixedClock().run(userId, "хм", zone);

        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.assistantText()).isEqualTo("Не понял, уточните пожалуйста.");
        assertThat(outcome.llmFailed()).isFalse();
    }

    @Test
    void run_marksLlmFailure() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(LlmToolResponse.unavailable());

        var outcome = loopWithFixedClock().run(userId, "закрой молоко", zone);

        assertThat(outcome.llmFailed()).isTrue();
        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.rejections()).isEmpty();
        assertThat(outcome.passes()).isEqualTo(1);
        verify(gateway, times(1)).callWithTools(any());
    }

    @Test
    void run_appliesDuplicateGuard() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(createTaskCall("купить молоко")), null));

        var outcome = loopWithFixedClock().run(userId, "купить молоко надо", zone);

        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.rejections()).anyMatch(r -> r.contains("T1"));
    }

    @Test
    void run_stopsWhenBudgetExhausted() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(searchCall("аптека")), null));

        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now, now.plusSeconds(40));

        var outcome = loop(clock).run(userId, "найди задачу", zone);

        verify(gateway, times(1)).callWithTools(any());
        assertThat(outcome.passes()).isEqualTo(1);
    }
}
