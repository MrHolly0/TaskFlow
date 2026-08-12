package ru.taskflow.assistant.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * AgentLoop считает бюджет времени через Clock — модуль должен сам объявить
 * этот бин, иначе Spring Boot не поднимет контекст. @ConditionalOnMissingBean
 * оставляет тестам и другим модулям возможность подменить бин своим.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
