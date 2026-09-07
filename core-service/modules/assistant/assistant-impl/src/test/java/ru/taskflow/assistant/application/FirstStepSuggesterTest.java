package ru.taskflow.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirstStepSuggesterTest {

    private final NlpGatewayService gateway = mock(NlpGatewayService.class);
    private final FirstStepSuggester suggester = new FirstStepSuggester(gateway, new ObjectMapper());

    @Test
    void generate_usesSeparateToolAndReturnsUsage() {
        when(gateway.callWithTools(any())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_first_step",
                        "{\"first_step\":\"Открой файл с исходными данными\"}")),
                null, 96, 14, false));

        var result = suggester.generate("подготовить отчёт по проекту", "свести цифры за месяц");

        assertThat(result.hint()).isEqualTo("Открой файл с исходными данными");
        assertThat(result.inputTokens()).isEqualTo(96);
        assertThat(result.outputTokens()).isEqualTo(14);

        var captor = forClass(LlmToolRequest.class);
        verify(gateway).callWithTools(captor.capture());
        assertThat(captor.getValue().tools()).hasSize(1);
        @SuppressWarnings("unchecked")
        var function = (java.util.Map<String, Object>) captor.getValue().tools().getFirst().get("function");
        assertThat(function.get("name")).isEqualTo("suggest_first_step");
    }

    @Test
    void generate_returnsNullForMalformedResponse() {
        when(gateway.callWithTools(any())).thenReturn(new LlmToolResponse(
                List.of(new LlmToolCall("call-1", "suggest_first_step", "{}")), null, 80, 4, false));

        assertThat(suggester.generate("длинная задача без ответа", null).hint()).isNull();
    }
}
