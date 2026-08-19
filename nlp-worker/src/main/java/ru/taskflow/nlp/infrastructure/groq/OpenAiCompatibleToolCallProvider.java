package ru.taskflow.nlp.infrastructure.groq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.taskflow.nlp.domain.RawToolCall;
import ru.taskflow.nlp.domain.ToolCallMessage;
import ru.taskflow.nlp.domain.ToolCallProvider;
import ru.taskflow.nlp.domain.ToolCallRequest;
import ru.taskflow.nlp.domain.ToolCallResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Вызов модели с инструментами (function calling) по OpenAI-совместимому
 * диалекту API. Ничего не интерпретирует — отдаёт вызовы инструментов как
 * есть, разбор и валидация остаются на стороне ядра. От конкретного
 * поставщика (Groq, ProxyAPI, ...) зависит только адрес и имя модели —
 * оба приходят из конфигурации.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiCompatibleToolCallProvider implements ToolCallProvider {

    private final GroqConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Override
    public ToolCallResult call(ToolCallRequest req) {
        try {
            return callApi(req);
        } catch (Exception e) {
            log.error("Failed to call tool-calling API", e);
            return ToolCallResult.empty();
        }
    }

    private ToolCallResult callApi(ToolCallRequest req) throws JsonProcessingException {
        var request = new HashMap<String, Object>();
        request.put("model", config.getLlmModel());
        request.put("messages", req.messages().stream().map(this::toApiMessage).toList());
        request.put("tools", req.tools());
        request.put("tool_choice", "auto");
        request.put("temperature", 0.1);

        String rawBody = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .body(request)
                .retrieve()
                .body(String.class);

        if (rawBody == null) {
            return ToolCallResult.empty();
        }

        return parseResponse(rawBody);
    }

    private ToolCallResult parseResponse(String rawBody) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(rawBody);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return ToolCallResult.empty();
        }

        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            return ToolCallResult.empty();
        }

        List<RawToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.get("tool_calls");
        if (toolCallsNode != null && toolCallsNode.isArray()) {
            for (JsonNode callNode : toolCallsNode) {
                JsonNode function = callNode.get("function");
                toolCalls.add(new RawToolCall(
                        callNode.path("id").asText(null),
                        function != null ? function.path("name").asText(null) : null,
                        function != null ? function.path("arguments").asText(null) : null
                ));
            }
        }

        String text = message.hasNonNull("content") ? message.get("content").asText() : null;

        JsonNode usage = root.get("usage");
        int inputTokens = usage != null ? usage.path("prompt_tokens").asInt(0) : 0;
        int outputTokens = usage != null ? usage.path("completion_tokens").asInt(0) : 0;

        return new ToolCallResult(toolCalls, text, inputTokens, outputTokens);
    }

    private Map<String, Object> toApiMessage(ToolCallMessage message) {
        Map<String, Object> apiMessage = new LinkedHashMap<>();
        apiMessage.put("role", message.role());

        if (message.isAssistantToolCalls()) {
            apiMessage.put("content", null);
            apiMessage.put("tool_calls", message.decodeToolCalls().stream()
                    .map(this::toApiToolCall)
                    .toList());
            return apiMessage;
        }

        apiMessage.put("content", message.content());
        if (message.toolCallId() != null) {
            apiMessage.put("tool_call_id", message.toolCallId());
        }
        if (message.name() != null) {
            apiMessage.put("name", message.name());
        }
        return apiMessage;
    }

    private Map<String, Object> toApiToolCall(RawToolCall call) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", call.name());
        function.put("arguments", call.argumentsJson());

        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", call.id());
        toolCall.put("type", "function");
        toolCall.put("function", function);
        return toolCall;
    }
}
