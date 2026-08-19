package ru.taskflow.nlp.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "app.llm")
@Data
public class ToolCallProvidersConfig {

    private List<Entry> providers = new ArrayList<>();

    @Data
    public static class Entry {
        private String name;
        private String baseUrl;
        private String apiKey;
        private String model;
        private boolean enabled = true;
    }
}
