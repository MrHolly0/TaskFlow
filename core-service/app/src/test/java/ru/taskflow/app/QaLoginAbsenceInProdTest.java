package ru.taskflow.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Не часть Task 4 буквально — тот про dev-token. Но то же самое утверждение
 * («путь отсутствует физически») сделано и в javadoc QaAuthController для
 * второго слоя из Task 1, а проверено там было только аннотацией. Раз
 * инфраструктура для настоящей HTTP-проверки уже здесь (см.
 * DevTokenAbsenceTest), закрыть тем же способом и этот пробел дёшево.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Testcontainers
class QaLoginAbsenceInProdTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void qaLogin_isPhysicallyAbsentInProdProfile() throws Exception {
        mockMvc.perform(post("/api/v1/auth/qa-login")
                        .header("X-Qa-Secret", "anything"))
                .andExpect(status().isNotFound());
    }
}
