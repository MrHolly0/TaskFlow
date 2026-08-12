package ru.taskflow.nlp.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Одно сообщение в истории диалога с моделью. Роли — как у Groq/OpenAI:
 * system, user, assistant, tool.
 * <p>
 * Сообщение ассистента с вызовами инструментов (assistantToolCalls) кодирует
 * список вызовов в content под тем же маркером, что распознаёт nlp-worker —
 * это отдельный деплоймент, общий код с ним недоступен, поэтому кодировка
 * продублирована здесь побайтово. Расшифровка на этой стороне не нужна:
 * decode делает только nlp-worker перед вызовом Groq.
 */
public record LlmMessage(String role, String content, String toolCallId, String name) {

    private static final String TOOL_CALLS_MARKER = "##tool_calls##";
    private static final String CALL_SEPARATOR = "|";
    private static final String FIELD_SEPARATOR = "~";

    public static LlmMessage system(String content) {
        return new LlmMessage("system", content, null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content, null, null);
    }

    public static LlmMessage toolResult(String toolCallId, String name, String content) {
        return new LlmMessage("tool", content, toolCallId, name);
    }

    public static LlmMessage assistantToolCalls(List<LlmToolCall> calls) {
        String payload = calls.stream()
                .map(LlmMessage::encodeCall)
                .collect(Collectors.joining(CALL_SEPARATOR));
        return new LlmMessage("assistant", TOOL_CALLS_MARKER + payload, null, null);
    }

    private static String encodeCall(LlmToolCall call) {
        return String.join(FIELD_SEPARATOR,
                encodeField(call.id()),
                encodeField(call.name()),
                encodeField(call.argumentsJson()));
    }

    private static String encodeField(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
