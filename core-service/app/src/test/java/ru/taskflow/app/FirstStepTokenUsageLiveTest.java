package ru.taskflow.app;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.FocusHintGeneration;
import ru.taskflow.assistant.api.FocusHintGenerator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("live")
class FirstStepTokenUsageLiveTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private FocusHintGenerator focusHintGenerator;

    @Test
    void measureFirstStepTokenUsage() {
        Assumptions.assumeTrue(System.getenv("GROQ_API_KEY") != null,
                "GROQ_API_KEY не задан — замер пропущен");
        List<FocusHintGeneration> results = List.of(
                focusHintGenerator.generate("подготовить квартальный отчёт", "свести данные о затратах"),
                focusHintGenerator.generate("организовать переезд в новую квартиру", "собрать вещи и заказать машину"),
                focusHintGenerator.generate("разобраться с документами по страховке", null));

        double averageInput = results.stream().mapToInt(FocusHintGeneration::inputTokens).average().orElseThrow();
        double averageOutput = results.stream().mapToInt(FocusHintGeneration::outputTokens).average().orElseThrow();
        System.out.printf("FIRST_STEP_TOKEN_USAGE input=%.1f output=%.1f samples=%d%n",
                averageInput, averageOutput, results.size());
        assertThat(results).allSatisfy(result -> {
            assertThat(result.hint()).isNotBlank();
            assertThat(result.inputTokens()).isPositive();
            assertThat(result.outputTokens()).isPositive();
        });
    }
}
