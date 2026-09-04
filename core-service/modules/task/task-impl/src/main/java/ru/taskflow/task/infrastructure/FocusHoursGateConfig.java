package ru.taskflow.task.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * §5.4: ключи, привязанные к часам работы учреждений, и границы окна —
 * в конфигурации, не в коде, чтобы список правился без пересборки.
 * Список по умолчанию пуст — без настройки в application.yml правило
 * никого не отсекает, это безопасное умолчание, не отсутствующая фича.
 */
@Configuration
@ConfigurationProperties(prefix = "app.focus.hours-gate")
@Data
public class FocusHoursGateConfig {
    private int windowStartHour = 9;
    private int windowEndHour = 19;
    private List<String> roots = List.of();
}
