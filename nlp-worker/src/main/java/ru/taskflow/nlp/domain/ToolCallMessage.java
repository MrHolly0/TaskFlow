package ru.taskflow.nlp.domain;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Одно сообщение в истории диалога с моделью. Роли — как у Groq/OpenAI:
 * system, user, assistant, tool.
 * <p>
 * Сообщение ассистента с вызовами инструментов (assistantToolCalls) не влезает
 * в четыре поля записи напрямую — сериализатор в Groq ждёт для него отдельный
 * массив tool_calls, а не текст content. Чтобы не раздувать контракт пятым
 * полем, список вызовов упаковывается в content под маркером, который
 * распознаёт только эта запись; наружу (в HTTP-запрос) он никогда не уходит —
 * GroqToolCallProvider разворачивает его обратно перед сборкой тела запроса.
 * Поля вызовов кодируются в base64, поэтому выбранные разделители (не входят
 * в алфавит base64) не могут случайно встретиться внутри значения.
 */
public record ToolCallMessage(String role, String content, String toolCallId, String name) {

    private static final String TOOL_CALLS_MARKER = "##tool_calls##";
    private static final String CALL_SEPARATOR = "|";
    private static final String FIELD_SEPARATOR = "~";

    public static ToolCallMessage system(String content) {
        return new ToolCallMessage("system", content, null, null);
    }

    public static ToolCallMessage user(String content) {
        return new ToolCallMessage("user", content, null, null);
    }

    public static ToolCallMessage toolResult(String toolCallId, String name, String content) {
        return new ToolCallMessage("tool", content, toolCallId, name);
    }

    public static ToolCallMessage assistantToolCalls(List<RawToolCall> calls) {
        String payload = calls.stream()
                .map(ToolCallMessage::encodeCall)
                .collect(Collectors.joining(CALL_SEPARATOR));
        return new ToolCallMessage("assistant", TOOL_CALLS_MARKER + payload, null, null);
    }

    public boolean isAssistantToolCalls() {
        return content != null && content.startsWith(TOOL_CALLS_MARKER);
    }

    public List<RawToolCall> decodeToolCalls() {
        if (!isAssistantToolCalls()) {
            return List.of();
        }
        String payload = content.substring(TOOL_CALLS_MARKER.length());
        if (payload.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(payload.split("\\" + CALL_SEPARATOR))
                .map(ToolCallMessage::decodeCall)
                .toList();
    }

    private static String encodeCall(RawToolCall call) {
        return String.join(FIELD_SEPARATOR,
                encodeField(call.id()),
                encodeField(call.name()),
                encodeField(call.argumentsJson()));
    }

    private static RawToolCall decodeCall(String encoded) {
        String[] fields = encoded.split(FIELD_SEPARATOR, -1);
        return new RawToolCall(decodeField(fields[0]), decodeField(fields[1]), decodeField(fields[2]));
    }

    private static String encodeField(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
