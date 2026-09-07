package ru.taskflow.task.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.focus.hint")
@Data
public class FocusHintConfig {
    private int minTitleWords = 4;
    private int estimateThresholdMinutes = 30;
}
