package ru.taskflow.nlp.domain;

import java.util.List;
import java.util.Map;

public record ToolCallRequest(List<ToolCallMessage> messages, List<Map<String, Object>> tools) {
}
