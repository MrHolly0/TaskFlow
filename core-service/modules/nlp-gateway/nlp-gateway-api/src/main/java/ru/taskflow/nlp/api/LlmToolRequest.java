package ru.taskflow.nlp.api;

import java.util.List;
import java.util.Map;

public record LlmToolRequest(List<LlmMessage> messages, List<Map<String, Object>> tools) {
}
