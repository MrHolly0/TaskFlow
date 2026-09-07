package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.FocusHintGeneration;
import ru.taskflow.assistant.api.FocusHintGenerator;
import ru.taskflow.nlp.api.LlmMessage;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;

import java.util.List;
import java.util.Map;

/**
 * Узкий вызов для гипотезы §5.1. Он не входит в основной контракт
 * ассистента и использует с ним только транспорт до модели.
 */
@Component
@RequiredArgsConstructor
public class FirstStepSuggester implements FocusHintGenerator {

    private static final String TOOL_NAME = "suggest_first_step";
    private static final int MAX_HINT_LENGTH = 200;

    private final NlpGatewayService nlpGatewayService;
    private final ObjectMapper objectMapper;

    @Override
    public FocusHintGeneration generate(String title, String description) {
        var request = new LlmToolRequest(List.of(
                LlmMessage.system(systemPrompt()),
                LlmMessage.user(userMessage(title, description))), List.of(tool()));
        LlmToolResponse response = nlpGatewayService.callWithTools(request);
        String hint = response.toolCalls().stream()
                .filter(call -> TOOL_NAME.equals(call.name()))
                .findFirst()
                .map(this::readHint)
                .orElse(null);
        return new FocusHintGeneration(hint, response.inputTokens(), response.outputTokens());
    }

    private String systemPrompt() {
        return """
                Сформулируй первый конкретный шаг, с которого человек может начать указанную задачу прямо сейчас.
                Шаг должен быть одним коротким действием в повелительной форме, наблюдаемым и выполнимым за несколько минут.
                Не пересказывай всю задачу, не составляй план, не добавляй факты, инструменты, имена или сроки, которых нет во входных данных.
                Если данных недостаточно, предложи безопасный первый шаг по уточнению или сбору исходной информации.
                Вызови suggest_first_step и заполни только поле first_step. Максимальная длина — 200 символов.""";
    }

    private String userMessage(String title, String description) {
        return """
                Название: %s
                Описание: %s""".formatted(
                title,
                description == null || description.isBlank() ? "нет" : description);
    }

    private Map<String, Object> tool() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", TOOL_NAME,
                        "description", "Сформулировать один первый шаг задачи",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of("first_step", Map.of(
                                        "type", "string",
                                        "description", "Одно короткое действие в повелительной форме")),
                                "required", List.of("first_step"))));
    }

    private String readHint(LlmToolCall call) {
        try {
            Map<String, Object> args = objectMapper.readValue(
                    call.argumentsJson(), new TypeReference<Map<String, Object>>() {});
            Object raw = args.get("first_step");
            if (raw == null) return null;
            String hint = raw.toString().trim();
            if (hint.isBlank()) return null;
            return hint.length() <= MAX_HINT_LENGTH ? hint : hint.substring(0, MAX_HINT_LENGTH);
        } catch (Exception e) {
            return null;
        }
    }
}
