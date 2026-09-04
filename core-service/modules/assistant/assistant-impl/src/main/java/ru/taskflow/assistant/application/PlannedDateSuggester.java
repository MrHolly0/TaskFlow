package ru.taskflow.assistant.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.nlp.api.LlmMessage;
import ru.taskflow.nlp.api.LlmToolCall;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.api.LlmToolResponse;
import ru.taskflow.nlp.api.NlpGatewayService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * §4.3, день исполнения отдельным узким вызовом — docs/vkr/реализация-трёх-задач.md,
 * часть 3.1. Вне основного контракта propose_actions намеренно: свой короткий
 * промпт, своя схема ответа с одним инструментом, ничего общего с
 * ToolRegistry/AssistantPromptBuilder/ActionValidator. Расширение контракта
 * уже измерено и отвергнуто (−4,5 пункта корректности, [−8,1; −0,9]).
 * <p>
 * Ничего в продукте этот класс не зовёт и не меняет — только стенд измерения
 * (ExperimentRunner) обращается к нему явно, за отдельной переменной
 * окружения. Продуктовая проводка — решение, которое принимается по
 * результатам замера, не входит в эту задачу.
 * <p>
 * Общий с контуром только транспорт до модели, NlpGatewayService.callWithTools —
 * та же абстракция, которой уже пользуются AssistantServiceImpl и
 * NotificationServiceImpl, ниже уровня промпта и не влияющая на него.
 */
@Component
@RequiredArgsConstructor
public class PlannedDateSuggester {

    private static final String TOOL_NAME = "suggest_planned_date";
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final NlpGatewayService nlpGatewayService;
    private final ObjectMapper objectMapper;

    public PlannedDateSuggestion suggest(String title, String description, String originalMessage,
            LocalDate today, ZoneId zone) {
        List<LlmMessage> messages = List.of(
                LlmMessage.system(systemPrompt()),
                LlmMessage.user(userMessage(title, description, originalMessage, today, zone)));
        LlmToolResponse response = nlpGatewayService.callWithTools(new LlmToolRequest(messages, List.of(tool())));

        LlmToolCall call = response.toolCalls().stream()
                .filter(c -> TOOL_NAME.equals(c.name()))
                .findFirst()
                .orElse(null);
        if (call == null) {
            // Модель не ответила вызовом инструмента (лимит, пустой ответ) —
            // отличимо от осознанного no_planned_date_needed: оба поля пусты.
            return new PlannedDateSuggestion(null, false, response.inputTokens(), response.outputTokens());
        }

        Map<String, Object> args = readArguments(call.argumentsJson());
        boolean noneNeeded = Boolean.TRUE.equals(args.get("no_planned_date_needed"));
        OffsetDateTime plannedDate = noneNeeded ? null : parseDate(args.get("planned_date"), zone);
        return new PlannedDateSuggestion(plannedDate, noneNeeded, response.inputTokens(), response.outputTokens());
    }

    private String systemPrompt() {
        return """
                Ты решаешь один узкий вопрос: для указанной задачи стоит ли \
                предположить день исполнения — не срок (обязательство пользователя), \
                а самостоятельная подсказка, когда разумно её сделать.

                Тебе даны название и описание, как их поняла система, и исходная \
                реплика пользователя целиком — в реплике может быть то, что не \
                попало в название или описание.

                Вызови suggest_planned_date с полем planned_date (дата в формате \
                YYYY-MM-DD), если в реплике есть мягкое указание на срок или период \
                без обязательства — не точная дата и не жёсткий дедлайн, а ощущение \
                подходящего времени. День должен попадать в этот период, а не быть \
                произвольным.

                Вызови suggest_planned_date с полем no_planned_date_needed=true, \
                если ни в названии, ни в описании, ни в реплике нет такого указания. \
                Не угадывай день там, где его нет: лучше явно отказаться, чем \
                предложить случайную дату.

                Заполняй ровно одно из двух полей.""";
    }

    private String userMessage(String title, String description, String originalMessage,
            LocalDate today, ZoneId zone) {
        return """
                Название: %s
                Описание: %s
                Исходная реплика пользователя: %s
                Сегодня: %s
                Часовой пояс пользователя: %s""".formatted(
                title,
                description == null || description.isBlank() ? "нет" : description,
                originalMessage,
                today.format(DATE_ONLY),
                zone.getId());
    }

    private Map<String, Object> tool() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", TOOL_NAME,
                        "description", "Предложить день исполнения задачи или явно отказаться от предложения",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "planned_date", Map.of(
                                                "type", "string",
                                                "description", "Предполагаемый день исполнения, YYYY-MM-DD"),
                                        "no_planned_date_needed", Map.of(
                                                "type", "boolean",
                                                "description", "true — день исполнения этой задаче не нужен")
                                ),
                                "required", List.of()
                        )
                )
        );
    }

    private Map<String, Object> readArguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private OffsetDateTime parseDate(Object raw, ZoneId zone) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.toString().trim()).atTime(LocalTime.NOON).atZone(zone).toOffsetDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}
