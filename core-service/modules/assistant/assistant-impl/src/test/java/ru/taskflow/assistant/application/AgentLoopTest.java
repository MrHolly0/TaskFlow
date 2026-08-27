package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.DeclineReason;
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
import java.util.concurrent.atomic.AtomicInteger;

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

    private final AssistantPromptBuilder promptBuilder = new AssistantPromptBuilder("Мунин");
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final ToolCallParser toolCallParser = new ToolCallParser(
            toolRegistry, new ActionValidator(taskService, Clock.fixed(now, zone)), new SummaryRenderer(), new ObjectMapper());
    private final DuplicateGuard duplicateGuard = new DuplicateGuard(new TitleSimilarity());
    private final TitleChangeGuard titleChangeGuard = new TitleChangeGuard();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TaskContextWindow window() {
        return new TaskContextWindow(
                "T1 · купить молоко",
                Map.of("T1", existingTaskId),
                Map.of("T1", "купить молоко"));
    }

    private TaskContextWindow movieWindow() {
        return new TaskContextWindow(
                "T1 · кино с настей",
                Map.of("T1", existingTaskId),
                Map.of("T1", "кино с настей"));
    }

    private AgentLoop loop(Clock clock) {
        return new AgentLoop(contextBuilder, promptBuilder, toolRegistry, gateway,
                toolCallParser, duplicateGuard, titleChangeGuard, taskService, clock, objectMapper);
    }

    private AgentLoop loopWithFixedClock() {
        return loop(Clock.fixed(now, zone));
    }

    private LlmToolCall completeTaskCall() {
        return new LlmToolCall("call-1", "propose_actions",
                "{\"actions\":[{\"type\":\"complete\",\"task_ref\":\"T1\"}]}");
    }

    private LlmToolCall emptyUpdateTaskCall() {
        return new LlmToolCall("call-update", "propose_actions",
                "{\"actions\":[{\"type\":\"update\",\"task_ref\":\"T1\"}]}");
    }

    private LlmToolCall updateWithFieldCall() {
        return new LlmToolCall("call-update", "propose_actions",
                "{\"actions\":[{\"type\":\"update\",\"task_ref\":\"T1\",\"priority\":\"HIGH\"}]}");
    }

    private LlmToolCall rescheduleTaskCall() {
        return new LlmToolCall("call-reschedule", "propose_actions",
                "{\"actions\":[{\"type\":\"reschedule\",\"task_ref\":\"T1\",\"new_deadline\":\"2026-08-13T15:00:00+03:00\"}]}");
    }

    private LlmToolCall cancelTaskCall() {
        return new LlmToolCall("call-cancel", "propose_actions",
                "{\"actions\":[{\"type\":\"cancel\",\"task_ref\":\"T1\"}]}");
    }

    private LlmToolCall createTaskCall(String title) {
        return new LlmToolCall("call-create", "propose_actions",
                "{\"actions\":[{\"type\":\"create\",\"title\":\"" + title + "\"}]}");
    }

    private LlmToolCall searchCall(String query) {
        return new LlmToolCall("call-search", "search_tasks", "{\"query\":\"" + query + "\"}");
    }

    private LlmToolCall askUserCall() {
        return new LlmToolCall("call-ask", "ask_user",
                "{\"question\":\"какую задачу закрыть?\",\"options\":[\"первую\",\"вторую\"]}");
    }

    private LlmToolCall noActionCall(String reason, String answer) {
        return new LlmToolCall("call-no-action", "no_action",
                "{\"reason\":\"" + reason + "\",\"answer\":\"" + answer + "\"}");
    }

    private LlmToolCall remindTaskCall(String reminderAt) {
        return new LlmToolCall("call-remind", "propose_actions",
                "{\"actions\":[{\"type\":\"remind\",\"task_ref\":\"T1\",\"reminder_at\":\"" + reminderAt + "\"}]}");
    }

    private LlmToolCall createWithReminderCall(String title, String reminderAt) {
        return new LlmToolCall("call-create-remind", "propose_actions",
                "{\"actions\":[{\"type\":\"create\",\"title\":\"" + title + "\",\"reminder_at\":\"" + reminderAt + "\"}]}");
    }

    // Модель делает ровно один вызов инструмента за ответ — двоякость и оба
    // альтернативных прочтения приходят внутри одного propose_actions,
    // ambiguous_reason на каждом из двух элементов, а не отдельным вызовом.
    private LlmToolCall ambiguousCompleteAndCreateCall(String title, String reason) {
        return new LlmToolCall("call-ambiguous", "propose_actions",
                "{\"actions\":[{\"type\":\"complete\",\"task_ref\":\"T1\",\"ambiguous_reason\":\"" + reason + "\"},"
                        + "{\"type\":\"create\",\"title\":\"" + title + "\",\"ambiguous_reason\":\"" + reason + "\"}]}");
    }

    private LlmToolResponse toolResponse(List<LlmToolCall> calls, String text) {
        return new LlmToolResponse(calls, text, 10, 5, false);
    }

    // Живой дефект: колонки input_tokens/output_tokens существуют, но между
    // LlmToolResponse и AgentOutcome не было связи — расход считался, но
    // терялся на выходе из AgentLoop.
    @Test
    void run_carriesTokenUsageFromSinglePass() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "закрой", zone);

        assertThat(outcome.inputTokens()).isEqualTo(10);
        assertThat(outcome.outputTokens()).isEqualTo(5);
    }

    @Test
    void run_sumsTokenUsageAcrossBothPasses() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("аптека")), null),
                toolResponse(List.of(), "готово"));
        when(taskService.search(userId, "аптека", false, 20))
                .thenReturn(List.of(taskResponse(foundTaskId, "Купить лекарство в аптеке")));

        var outcome = loopWithFixedClock().run(userId, "найди задачу про аптеку", zone);

        assertThat(outcome.inputTokens()).isEqualTo(20);
        assertThat(outcome.outputTokens()).isEqualTo(10);
    }

    @Test
    void run_reportsZeroTokensWhenLlmFailed() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(LlmToolResponse.unavailable());

        var outcome = loopWithFixedClock().run(userId, "закрой молоко", zone);

        assertThat(outcome.inputTokens()).isZero();
        assertThat(outcome.outputTokens()).isZero();
    }

    // Clock.fixed() в loopWithFixedClock() никогда не тикает — задержки на нём
    // всегда были бы нулём, что не отличило бы «посчитано» от «не посчитано».
    // Здесь часы монотонно продвигаются на каждый вызов instant().
    private Clock advancingClock() {
        Clock clock = mock(Clock.class);
        AtomicInteger tick = new AtomicInteger();
        when(clock.instant()).thenAnswer(invocation -> now.plusMillis(tick.getAndIncrement() * 100L));
        return clock;
    }

    // Живой дефект: задержки по этапам (первый/второй проход, полное время)
    // нужны для стенда эксперимента (ВКР), но AgentLoop их не измерял вовсе.
    @Test
    void run_capturesPositiveFirstPassAndTotalLatencyOnSinglePass() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        var outcome = loop(advancingClock()).run(userId, "закрой", zone);

        assertThat(outcome.firstPassLatencyMs()).isPositive();
        assertThat(outcome.totalLatencyMs()).isPositive();
        assertThat(outcome.secondPassLatencyMs()).isZero();
    }

    @Test
    void run_capturesPositiveSecondPassLatencyAndTotalCoversBothPasses() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("аптека")), null),
                toolResponse(List.of(), "готово"));
        when(taskService.search(userId, "аптека", false, 20))
                .thenReturn(List.of(taskResponse(foundTaskId, "Купить лекарство в аптеке")));

        var outcome = loop(advancingClock()).run(userId, "найди задачу про аптеку", zone);

        assertThat(outcome.firstPassLatencyMs()).isPositive();
        assertThat(outcome.secondPassLatencyMs()).isPositive();
        assertThat(outcome.totalLatencyMs())
                .isGreaterThanOrEqualTo(outcome.firstPassLatencyMs() + outcome.secondPassLatencyMs());
    }

    @Test
    void run_reportsZeroLatencyWhenLlmFailed() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(LlmToolResponse.unavailable());

        var outcome = loopWithFixedClock().run(userId, "закрой молоко", zone);

        assertThat(outcome.firstPassLatencyMs()).isZero();
        assertThat(outcome.secondPassLatencyMs()).isZero();
        // totalLatencyMs остаётся 0 у Clock.fixed(): часы не тикают, а не потому
        // что withLatencies его не проставил — это покрыто позитивными тестами выше.
    }

    private TaskResponse taskResponse(UUID id, String title) {
        return new TaskResponse(id, title, null, TaskPriority.MEDIUM, TaskStatus.TODO,
                null, null, TaskSource.MANUAL, null, null, List.of(),
                OffsetDateTime.now(), OffsetDateTime.now(), null, List.of());
    }

    @Test
    void run_returnsActionsFromSinglePass() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        // Короткая реплика (< 8 символов) не читается как название задачи —
        // не задевает разбор двоякости из Task 5, тест проверяет только базовый проход.
        var outcome = loopWithFixedClock().run(userId, "закрой", zone);

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

    // Блок Б: «покажи задачи на завтра» — вопрос о данных, не команда.
    // no_action заменяет действие, а не дополняет его — actions пуст.
    @Test
    void run_returnsDeclineWhenModelCallsNoActionInFirstPass() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(noActionCall("question", "На завтра задач нет.")), null));

        var outcome = loopWithFixedClock().run(userId, "покажи задачи на завтра", zone);

        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.declineReason()).isEqualTo(DeclineReason.QUESTION);
        assertThat(outcome.assistantText()).isEqualTo("На завтра задач нет.");
        assertThat(outcome.passes()).isEqualTo(1);
        assertThat(outcome.llmFailed()).isFalse();
        verify(gateway, times(1)).callWithTools(any());
    }

    @Test
    void run_returnsDeclineForChitchat() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(noActionCall("chitchat", "Пожалуйста!")), null));

        var outcome = loopWithFixedClock().run(userId, "спасибо", zone);

        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.declineReason()).isEqualTo(DeclineReason.CHITCHAT);
        assertThat(outcome.assistantText()).isEqualTo("Пожалуйста!");
    }

    // Пункт 2: напоминание на прошедший момент — RM-13/14/15, живой прогон
    // 27.08 показал модель вместо этого предлагала create.
    @Test
    void run_returnsDeclineForPastReminderTime() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(noActionCall("past", "Это время уже прошло.")), null));

        var outcome = loopWithFixedClock().run(userId, "напомни про курсовую вчера в 10 утра", zone);

        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.declineReason()).isEqualTo(DeclineReason.PAST);
        assertThat(outcome.assistantText()).isEqualTo("Это время уже прошло.");
    }

    // Модель может отказаться и после поиска — «покажи задачи на завтра»,
    // если сперва свериться со списком через search_tasks (Б5).
    @Test
    void run_returnsDeclineAfterSearchInSecondPass() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("завтра")), null),
                toolResponse(List.of(noActionCall("question", "На завтра задач нет.")), null));
        when(taskService.search(userId, "завтра", false, 20)).thenReturn(List.of());

        var outcome = loopWithFixedClock().run(userId, "покажи задачи на завтра", zone);

        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.declineReason()).isEqualTo(DeclineReason.QUESTION);
        assertThat(outcome.assistantText()).isEqualTo("На завтра задач нет.");
        assertThat(outcome.passes()).isEqualTo(2);
        // расход второго прохода не должен теряться при отказе (та же логика,
        // что и для обычного пути через withSecondPassLatency)
        assertThat(outcome.inputTokens()).isEqualTo(20);
        assertThat(outcome.outputTokens()).isEqualTo(10);
    }

    @Test
    void run_declineReasonSurvivesLatencyOverlay() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(noActionCall("unclear", "Не понял, уточните.")), null));

        var outcome = loop(advancingClock()).run(userId, "непонятно что", zone);

        // withLatencies() перестраивает AgentOutcome поверх результата
        // runFirstPass — declineReason не должен теряться при этой перестройке.
        assertThat(outcome.declineReason()).isEqualTo(DeclineReason.UNCLEAR);
        assertThat(outcome.totalLatencyMs()).isPositive();
    }

    // Блок В: напоминание на существующую задачу — срок задачи не трогает.
    @Test
    void run_returnsRemindActionForExistingTask() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(remindTaskCall("2026-08-13T09:00:00+03:00")), null));

        var outcome = loopWithFixedClock().run(userId, "напомни про молоко завтра в 9", zone);

        assertThat(outcome.actions()).hasSize(1);
        var action = outcome.actions().getFirst();
        assertThat(action.type()).isEqualTo(AssistantActionType.REMIND);
        assertThat(action.targetTaskId()).isEqualTo(existingTaskId);
        assertThat(action.payload()).containsEntry("reminder_at", "2026-08-13T09:00:00+03:00");
    }

    // Создать задачу сразу с напоминанием — одно действие, не два: ссылки на
    // ещё не созданную задачу в том же пакете не существует.
    @Test
    void run_returnsCreateActionWithReminderAt() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(createWithReminderCall("позвонить маме", "2026-08-13T10:00:00+03:00")), null));

        var outcome = loopWithFixedClock().run(userId, "напомни завтра в 10 позвонить маме", zone);

        assertThat(outcome.actions()).hasSize(1);
        var action = outcome.actions().getFirst();
        assertThat(action.type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(action.payload()).containsEntry("title", "позвонить маме");
        assertThat(action.payload()).containsEntry("reminder_at", "2026-08-13T10:00:00+03:00");
        // Срок и напоминание — разные вещи: создание с одним лишь напоминанием
        // не подставляет reminder_at в качестве deadline.
        assertThat(action.payload()).doesNotContainKey("deadline");
    }

    @Test
    void run_stopsOnAmbiguousMarkWithBothAlternatives() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(
                List.of(ambiguousCompleteAndCreateCall("кино", "не понял, про какое кино речь")),
                null));

        var outcome = loopWithFixedClock().run(userId, "закрой кино", zone);

        verify(gateway, times(1)).callWithTools(any());
        assertThat(outcome.ambiguous()).isTrue();
        assertThat(outcome.ambiguityReason()).isEqualTo("не понял, про какое кино речь");
        assertThat(outcome.actions()).hasSize(2);
        assertThat(outcome.actions().get(0).accepted()).isTrue();
        assertThat(outcome.actions().get(1).accepted()).isFalse();
    }

    @Test
    void run_doesNotMarkAmbiguousByDefault() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        // Реплика почти совпадает с названием T1 — сходство выше порога дубля,
        // это дело DuplicateGuard, не двоякость.
        var outcome = loopWithFixedClock().run(userId, "купить молоко", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.ambiguityReason()).isNull();
        assertThat(outcome.actions()).hasSize(1);
    }

    // Task 5, вторая редакция: двоякость — результат разбора (структура +
    // сходство с DuplicateGuard), не особый случай и не список глаголов.

    // Живой дефект: «сходил в кино» при активной «кино с настей» — модель
    // верно предложила complete_task, а эта ветка синтезировала вторым
    // вариантом «создать» и он же оказывался выбран из быстрого добавления.
    // «Закрыть X» и «создать X» — не два правдоподобных прочтения одной
    // реплики так, как ими являются «изменить X» и «создать X» (см. ниже,
    // run_offersBothAlternativesForASecondPhraseAgainstSameTask, тот случай
    // остаётся). Раз модель уже решила, что дело сделано, предлагать завести
    // его заново незачем.
    @Test
    void run_doesNotOfferCreateAlternativeWhenModelClosesMatchingTask() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "закрыть кино", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.COMPLETE);
        assertThat(outcome.actions().getFirst().accepted()).isTrue();
    }

    // Живой дефект (эксперимент Б, UE-01…UE-04): update/reschedule всегда
    // несут значение, извлечённое из самой реплики (ActionValidator не
    // пропускает их иначе — «нечего менять» / нет нового срока), поэтому
    // они больше не годятся как пример настоящей двоякости. Голая ссылка
    // без параметров (cancel/complete) годится по-прежнему — этот тест
    // теперь про неё, а не про update.
    @Test
    void run_offersBothAlternativesForASecondPhraseAgainstSameTask() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(cancelTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "отменить планы на кино", zone);

        assertThat(outcome.ambiguous()).isTrue();
        assertThat(outcome.actions()).hasSize(2);
        // Причина собирается из названия задачи, не из готовой сводки с
        // глаголом (была бы «...означать «Отменить — кино с настей»...»).
        assertThat(outcome.ambiguityReason()).isEqualTo("реплика могла означать «кино с настей», а могла — новую задачу");
        assertThat(outcome.inputTokens()).isEqualTo(10);
        assertThat(outcome.outputTokens()).isEqualTo(5);
    }

    // Живой дефект (эксперимент Б, UE-01, «перенеси встречу с директором на
    // завтра на 15»): reschedule всегда несёт новый срок, извлечённый из
    // реплики, — явная команда с параметром двоякой не бывает, в отличие
    // от голой ссылки («закрыть кино»). Ветка раньше всё равно предлагала
    // альтернативу «создать», хотя вторым правдоподобным прочтением тут и
    // не пахнет.
    @Test
    void run_doesNotOfferCreateAlternativeForRescheduleWithExtractedDeadline() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(rescheduleTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "перенеси кино на завтра на 15", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.RESCHEDULE);
    }

    // То же самое для update: «изменить планы на кино» с priority=HIGH —
    // раньше канонический пример этой ветки (сама ветка и была построена
    // под него), теперь исключение: значение поля уже извлечено, второе
    // прочтение неправдоподобно.
    @Test
    void run_doesNotOfferCreateAlternativeForUpdateWithExtractedField() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(updateWithFieldCall()), null));

        var outcome = loopWithFixedClock().run(userId, "изменить планы на кино", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.UPDATE);
    }

    @Test
    void run_createsStandaloneTaskWhenModelSilentAndTextReadsAsTitle() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(), null));

        var outcome = loopWithFixedClock().run(userId, "изменить планы на кино", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
    }

    @Test
    void run_doesNotOfferChoiceOnUnambiguousPhraseWithEmptyWindow() {
        when(contextBuilder.build(userId)).thenReturn(new TaskContextWindow("", Map.of(), Map.of()));
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(), null));

        var outcome = loopWithFixedClock().run(userId, "купить корм коту", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
    }

    @Test
    void run_treatsHighSimilarityAsDuplicateNotAmbiguity() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        // Совпадает почти дословно с названием T1 («кино с настей») — при
        // трёхсловном названии любая приставка роняет жаккар ниже 0.8, порог
        // реально различим только на точном/почти точном совпадении.
        var outcome = loopWithFixedClock().run(userId, "кино с настей", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.actions()).hasSize(1);
    }

    @Test
    void run_doesNotOfferChoiceWhenModelActedButPhraseIsAQuestion() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "закрыть кино уже было?", zone);

        assertThat(outcome.ambiguous()).isFalse();
        assertThat(outcome.actions()).hasSize(1);
    }

    // Раньше быстрое добавление переставляло местами: create_task первым и
    // выбранным, даже когда модель уже уверенно предложила своё действие —
    // ровно так «сходил в кино» превращалось в предложенную по умолчанию
    // новую задачу. Мы дополняем предложение модели альтернативой, а не
    // спорим с ним, независимо от точки входа; «создание первым» остаётся
    // только для случая, когда модель промолчала и вариант синтезировали мы
    // сами (run_createsStandaloneTaskWhenModelSilentAndTextReadsAsTitle).
    @Test
    void run_keepsModelActionFirstEvenForQuickAdd() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(cancelTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "отменить планы на кино", zone,
                ru.taskflow.assistant.api.AssistantEntryPoint.QUICK_ADD);

        assertThat(outcome.actions().get(0).type()).isEqualTo(AssistantActionType.CANCEL);
        assertThat(outcome.actions().get(0).accepted()).isTrue();
        assertThat(outcome.actions().get(1).type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(outcome.actions().get(1).accepted()).isFalse();
    }

    @Test
    void run_keepsModelActionFirstForChat() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(cancelTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "отменить планы на кино", zone,
                ru.taskflow.assistant.api.AssistantEntryPoint.CHAT);

        assertThat(outcome.actions().get(0).type()).isEqualTo(AssistantActionType.CANCEL);
        assertThat(outcome.actions().get(0).accepted()).isTrue();
        assertThat(outcome.actions().get(1).type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(outcome.actions().get(1).accepted()).isFalse();
    }

    /**
     * Обнаружено живым прогоном и здесь же зачинено. Модель на «изменить
     * планы на кино» реально вызывает update_task(task_ref=T1) без единого
     * поля — ActionValidator отклоняет это как «нечего менять» ДО того, как
     * действие попадёт в actions(). Раньше rejections() после этого был не
     * пуст, modelSaidNothing — ложно, и обе ветки двоякости молчали: ни
     * «промолчала» (rejections не пуст), ни «предложила действие» (actions
     * пуст — отклонённое действие туда не попадает). Реплика вырождалась в
     * деградацию — тот же баг, который чинит эта задача, просто с другой
     * стороны: не список глаголов, а валидатор. Чинится тем же путём:
     * ActionValidator теперь возвращает targetTaskId и при отказе (ярлык
     * разрешился, дальше не собралось), ToolCallParser прокидывает его как
     * rejectedTarget, а modelSaidNothing принимает его как замену пустым
     * rejections. Дальше — обычный путь запасного создания (единственного
     * действия, не двух альтернатив: отклонённая попытка не даёт валидного
     * второго варианта, который можно было бы предложить рядом).
     */
    @Test
    void run_unrelatedRejectionStillBlocksFallback() {
        // Task 0 части 3б: отказ фильтра — уже принятое решение, подменять
        // его сырой репликой нельзя. rejectedTarget тут не выставляется —
        // отказ не про разрешённый ярлык, а про неизвестный инструмент.
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(new LlmToolCall("call-x", "delete_everything", "{}")), null));

        var outcome = loopWithFixedClock().run(userId, "изменить планы на кино", zone);

        assertThat(outcome.actions()).isEmpty();
    }

    @Test
    void run_emptyUpdateAttemptFallsBackToCreate() {
        when(contextBuilder.build(userId)).thenReturn(movieWindow());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(emptyUpdateTaskCall()), null));

        var outcome = loopWithFixedClock().run(userId, "изменить планы на кино", zone);

        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(outcome.ambiguous()).isFalse();
    }

    /**
     * Зачинено третьим признаком. Живой прогон подтвердил гипотезу: на
     * «покажи задачи на завтра» модель реально зовёт search_tasks (passes=2),
     * находит пусто и отвечает текстом, действий не предлагая. Сочетание
     * «поиск состоялся + действий и отказов нет» — это обращение к данным,
     * не название задачи, и запасной путь больше не создаёт задачу в этом
     * случае. «купить корм коту» на пустом списке в поиск не ходит вовсе
     * (см. run_doesNotOfferChoiceOnUnambiguousPhraseWithEmptyWindow) —
     * признак их и правда различает, ничего не подгонялось под ответ.
     */
    @Test
    void run_doesNotCreateTaskWhenSearchFoundNothingToPropose() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("завтра")), null),
                toolResponse(List.of(), "На завтра задач нет."));
        when(taskService.search(userId, "завтра", false, 20)).thenReturn(List.of());

        var outcome = loopWithFixedClock().run(userId, "покажи задачи на завтра", zone);

        assertThat(outcome.actions()).isEmpty();
        assertThat(outcome.passes()).isEqualTo(2);
    }

    /**
     * ОСТАТОЧНЫЙ, УЖЕ НЕ ЖИВОЙ СЛУЧАЙ. Если бы модель на такую реплику не
     * искала вовсе (single pass, ни действий, ни отказов) — структура и
     * сходство её от «купить корм коту» не отличили бы. Живой прогон
     * показал, что для «покажи задачи на завтра» модель реально ходит в
     * поиск (см. run_doesNotCreateTaskWhenSearchFoundNothingToPropose) —
     * этот путь для неё уже не актуален, оставлен как документация границы
     * признака «поиск + пусто», а не как открытый дефект.
     */
    @Test
    void run_singlePassListingPhraseStillBecomesATask_narrowResidualCase() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(), null));

        var outcome = loopWithFixedClock().run(userId, "покажи задачи на завтра", zone);

        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().payload()).containsEntry("title", "покажи задачи на завтра");
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
    void run_createsStandaloneTaskWhenModelReturnsNoToolCalls() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(), "Ничего не нашлось."));

        var outcome = loopWithFixedClock().run(userId, "Поменять название ложному варнингу", zone);

        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(outcome.actions().getFirst().payload()).containsEntry("title", "Поменять название ложному варнингу");
    }

    @Test
    void run_threadsEntryPointIntoPrompt() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));
        var captor = org.mockito.ArgumentCaptor.forClass(ru.taskflow.nlp.api.LlmToolRequest.class);

        loopWithFixedClock().run(userId, "закрой молоко", zone, ru.taskflow.assistant.api.AssistantEntryPoint.QUICK_ADD);

        verify(gateway).callWithTools(captor.capture());
        String systemPrompt = captor.getValue().messages().getFirst().content();
        assertThat(systemPrompt).contains("быстрого добавления");
    }

    @Test
    void run_defaultOverloadUsesChatEntryPoint() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(toolResponse(List.of(completeTaskCall()), null));
        var captor = org.mockito.ArgumentCaptor.forClass(ru.taskflow.nlp.api.LlmToolRequest.class);

        loopWithFixedClock().run(userId, "закрой молоко", zone);

        verify(gateway).callWithTools(captor.capture());
        String systemPrompt = captor.getValue().messages().getFirst().content();
        assertThat(systemPrompt).doesNotContain("быстрого добавления");
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
    void run_dropsCreateRepeatedAcrossPasses() {
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("аптека"), createTaskCall("Записаться к стоматологу")), null),
                toolResponse(List.of(createTaskCall("записаться к стоматологу")), null));
        when(taskService.search(userId, "аптека", false, 20))
                .thenReturn(List.of(taskResponse(foundTaskId, "Купить лекарство в аптеке")));

        var outcome = loopWithFixedClock().run(userId, "найди задачу про аптеку, запишись к стоматологу", zone);

        assertThat(outcome.actions()).hasSize(1);
        assertThat(outcome.actions().getFirst().ordinal()).isEqualTo(1);
        assertThat(outcome.rejections()).anyMatch(r -> r.contains("уже предложено"));
    }

    @Test
    void run_serializesSearchResultsAsValidJson() throws Exception {
        String trickyTitle = "Купить \"молоко\"\nи хлеб";
        when(contextBuilder.build(userId)).thenReturn(window());
        when(gateway.callWithTools(any())).thenReturn(
                toolResponse(List.of(searchCall("молоко")), null),
                toolResponse(List.of(), "готово"));
        when(taskService.search(userId, "молоко", false, 20))
                .thenReturn(List.of(taskResponse(foundTaskId, trickyTitle)));

        loopWithFixedClock().run(userId, "найди задачу про молоко", zone);

        var captor = org.mockito.ArgumentCaptor.forClass(LlmToolRequest.class);
        verify(gateway, times(2)).callWithTools(captor.capture());
        String toolResultContent = captor.getAllValues().get(1).messages().get(3).content();

        var parsed = objectMapper.readTree(toolResultContent);
        assertThat(parsed.get(0).get("title").asText()).isEqualTo(trickyTitle);
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
