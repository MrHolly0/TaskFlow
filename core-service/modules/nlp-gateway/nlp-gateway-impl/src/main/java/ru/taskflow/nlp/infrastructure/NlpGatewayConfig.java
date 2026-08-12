package ru.taskflow.nlp.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.nlp")
@Data
public class NlpGatewayConfig {
    private String workerUrl = "http://localhost:8081";
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 30;
    // отдельный, короче: путь вызова инструментов — интерактивный, человек ждёт ответа в диалоге,
    // повторов у него нет (max-attempts:1), поэтому и таймаут прохода держим в бюджете спеки (20с),
    // а не на уровне пакетного разбора текста/голоса.
    private int toolCallReadTimeoutSeconds = 20;
}
