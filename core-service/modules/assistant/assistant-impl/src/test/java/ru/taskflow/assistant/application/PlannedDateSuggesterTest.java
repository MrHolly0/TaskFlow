package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannedDateSuggesterTest {

    // Отличается от системной зоны машины, чтобы тест не мог случайно
    // совпасть с умолчанием и замаскировать игнорирование ZoneId.
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 4);

    private final NlpGatewayService gateway = mock(NlpGatewayService.class);
    private final PlannedDateSuggester suggester = new PlannedDateSuggester(gateway, new ObjectMapper());

    @Test
    void suggest_returnsPlannedDate_whenModelProposesOne() {
        when(gateway.callWithTools(anyRequest())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_planned_date", "{\"planned_date\":\"2026-09-10\"}")),
                null, 120, 15, false));

        var result = suggester.suggest("разобрать бумаги по страховке", null,
                "на этой неделе нужно разобрать бумаги по страховке", TODAY, ZONE);

        assertThat(result.noPlannedDateNeeded()).isFalse();
        assertThat(result.plannedDate()).isEqualTo(
                OffsetDateTime.parse("2026-09-10T12:00:00+09:00"));
        assertThat(result.inputTokens()).isEqualTo(120);
        assertThat(result.outputTokens()).isEqualTo(15);
    }

    @Test
    void suggest_returnsNoPlannedDateNeeded_whenModelDeclines() {
        when(gateway.callWithTools(anyRequest())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_planned_date", "{\"no_planned_date_needed\":true}")),
                null, 118, 10, false));

        var result = suggester.suggest("разобрать шкаф с одеждой", null, "разобрать шкаф с одеждой", TODAY, ZONE);

        assertThat(result.plannedDate()).isNull();
        assertThat(result.noPlannedDateNeeded()).isTrue();
    }

    @Test
    void suggest_distinguishesModelSilence_fromExplicitDecline() {
        // Модель не вызвала инструмент вовсе (лимит, пустой ответ) — не то же
        // самое, что осознанный no_planned_date_needed=true.
        when(gateway.callWithTools(anyRequest())).thenReturn(new LlmToolResponse(List.of(), null, 0, 0, true));

        var result = suggester.suggest("что угодно", null, "что угодно", TODAY, ZONE);

        assertThat(result.plannedDate()).isNull();
        assertThat(result.noPlannedDateNeeded()).isFalse();
    }

    @Test
    void suggest_sendsExactlyOneTool_separateFromProposeActions() {
        when(gateway.callWithTools(anyRequest())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_planned_date", "{\"no_planned_date_needed\":true}")),
                null, 100, 10, false));

        suggester.suggest("вынести мусор", null, "вынести мусор", TODAY, ZONE);

        var captor = forClass(LlmToolRequest.class);
        verify(gateway).callWithTools(captor.capture());
        assertThat(captor.getValue().tools()).hasSize(1);
        @SuppressWarnings("unchecked")
        var function = (java.util.Map<String, Object>) captor.getValue().tools().getFirst().get("function");
        assertThat(function.get("name")).isEqualTo("suggest_planned_date");
    }

    @Test
    void suggest_userMessage_includesTitleDescriptionOriginalMessageDateAndZone() {
        when(gateway.callWithTools(anyRequest())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_planned_date", "{\"no_planned_date_needed\":true}")),
                null, 100, 10, false));

        suggester.suggest("сдать отчёт", "квартальный, для бухгалтерии",
                "надо не забыть сдать квартальный отчёт для бухгалтерии", TODAY, ZONE);

        var captor = forClass(LlmToolRequest.class);
        verify(gateway).callWithTools(captor.capture());
        String userMessage = captor.getValue().messages().stream()
                .filter(m -> "user".equals(m.role()))
                .findFirst().orElseThrow().content();
        assertThat(userMessage).contains("сдать отчёт");
        assertThat(userMessage).contains("квартальный, для бухгалтерии");
        assertThat(userMessage).contains("надо не забыть сдать квартальный отчёт для бухгалтерии");
        assertThat(userMessage).contains("2026-09-04");
        assertThat(userMessage).contains("Asia/Tokyo");
    }

    @Test
    void suggest_missingDescription_isLabeledExplicitly() {
        when(gateway.callWithTools(anyRequest())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_planned_date", "{\"no_planned_date_needed\":true}")),
                null, 100, 10, false));

        suggester.suggest("вынести мусор", null, "вынести мусор", TODAY, ZONE);

        var captor = forClass(LlmToolRequest.class);
        verify(gateway).callWithTools(captor.capture());
        String userMessage = captor.getValue().messages().stream()
                .filter(m -> "user".equals(m.role()))
                .findFirst().orElseThrow().content();
        assertThat(userMessage).contains("Описание: нет");
    }

    @Test
    void suggest_systemPrompt_doesNotQuoteDatasetPhrasesVerbatim() {
        // Регрессия: промпт не должен перечислять конкретные обороты из
        // nlp-dataset-planned-date-auto.json — иначе он не обобщение
        // категории, а подгонка под конкретные реплики набора.
        when(gateway.callWithTools(anyRequest())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_planned_date", "{\"no_planned_date_needed\":true}")),
                null, 100, 10, false));

        suggester.suggest("вынести мусор", null, "вынести мусор", TODAY, ZONE);

        var captor = forClass(LlmToolRequest.class);
        verify(gateway).callWithTools(captor.capture());
        String systemMessage = captor.getValue().messages().stream()
                .filter(m -> "system".equals(m.role()))
                .findFirst().orElseThrow().content();
        assertThat(systemMessage).doesNotContain("на этой неделе");
        assertThat(systemMessage).doesNotContain("в выходные");
        assertThat(systemMessage).doesNotContain("на днях");
        assertThat(systemMessage).doesNotContain("пока не забыл");
    }

    private LlmToolRequest anyRequest() {
        return org.mockito.ArgumentMatchers.any();
    }
}
