package ru.taskflow.user.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * LoginCodeService считает окна частоты запроса кода через Clock — модуль
 * должен сам объявить этот бин, иначе Spring Boot не поднимет контекст.
 * @ConditionalOnMissingBean оставляет тестам и другим модулям возможность
 * подменить бин своим.
 *
 * Явное имя конфигурации — на классе с тем же простым именем ClockConfig уже
 * стоит @Configuration в assistant-impl; без явного имени бины конфликтуют
 * по умолчанию присваиваемому имени (короткое имя класса без пакета).
 */
@Configuration("userClockConfig")
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
