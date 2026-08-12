package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что весь граф бинов приложения собирается: сюда бы попала
 * и потеря бина Clock у AgentLoop, и более ранняя потеря ObjectMapper
 * у ProposalMapper из части 2а. Полный контекст, реальные миграции
 * Liquibase на Testcontainers Postgres — без Redis и Telegram-токена
 * тест всё равно проходит: Redis-соединение у Spring Data Redis
 * ленивое (не открывается при поднятии контекста), а регистрация
 * Telegram-бота в TelegramBotConfig сама себя отключает при пустом
 * app.telegram.bot-token (значение по умолчанию в тестах).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TaskFlowApplicationSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Test
    void contextLoads(ApplicationContext context) {
        assertThat(context.getBean(java.time.Clock.class)).isNotNull();
    }
}
