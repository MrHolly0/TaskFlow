package ru.taskflow.nlp.infrastructure.groq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Проверяет при старте, что настроенная модель числится в списке /models у
 * провайдера — Groq однажды сняла модель без предупреждения, и об этом узнали
 * случайно, во время живой проверки другой задачи. Результат кэшируется и
 * отдаётся в /actuator/health; сама проверка старт не валит — провайдер может
 * быть временно недоступен, а сервис обязан подниматься.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ModelAvailabilityChecker implements ApplicationRunner, HealthIndicator {

    private final GroqConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    private volatile Health lastCheck = Health.unknown().withDetail("reason", "проверка ещё не выполнялась").build();

    @Override
    public void run(ApplicationArguments args) {
        lastCheck = checkModel();
    }

    @Override
    public Health health() {
        return lastCheck;
    }

    private Health checkModel() {
        List<String> availableModels;
        try {
            availableModels = fetchAvailableModels();
        } catch (Exception e) {
            log.warn("Не удалось проверить доступность модели {} при старте: {}", config.getLlmModel(), e.getMessage());
            return Health.unknown().withDetail("model", config.getLlmModel()).withDetail("error", e.getMessage()).build();
        }

        if (availableModels.contains(config.getLlmModel())) {
            return Health.up().withDetail("model", config.getLlmModel()).build();
        }

        log.error("Настроенная модель {} отсутствует у провайдера. Доступные модели: {}",
                config.getLlmModel(), availableModels);
        return Health.down()
                .withDetail("model", config.getLlmModel())
                .withDetail("available", availableModels)
                .build();
    }

    private List<String> fetchAvailableModels() throws Exception {
        String rawBody = restClient.get()
                .uri("/models")
                .header("Authorization", "Bearer " + config.getApiKey())
                .retrieve()
                .body(String.class);

        List<String> ids = new ArrayList<>();
        if (rawBody == null) {
            return ids;
        }

        JsonNode data = objectMapper.readTree(rawBody).get("data");
        if (data != null && data.isArray()) {
            for (JsonNode model : data) {
                ids.add(model.path("id").asText(null));
            }
        }
        return ids;
    }
}
