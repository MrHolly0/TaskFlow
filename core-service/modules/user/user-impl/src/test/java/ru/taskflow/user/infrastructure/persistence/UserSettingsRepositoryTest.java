package ru.taskflow.user.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 20.08.2026: значение notify_email по умолчанию для НОВЫХ строк меняется на
 * false (личный почтовый ящик, лимит писем в сутки). Существующих
 * пользователей это трогать не должно — молча выключить работающие
 * уведомления человеку, который на них рассчитывает, нельзя. Тест проверяет
 * именно это: у уже сохранённой строки со значением true чтение не должно
 * подменить его новым дефолтом.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserSettingsRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = UserSettingsRepository.class)
    @EntityScan(basePackageClasses = UserSettingsJpaEntity.class)
    static class TestConfig {
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");
    }

    @Autowired
    private UserSettingsRepository settingsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void existingRowWithEmailEnabled_keepsItsValueAfterReload() {
        var user = userRepository.saveAndFlush(new UserJpaEntity());
        var settings = new UserSettingsJpaEntity();
        settings.setUser(user);
        settings.setNotifyEmail(true);
        settingsRepository.saveAndFlush(settings);

        entityManager.clear();

        var reloaded = settingsRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(reloaded.isNotifyEmail())
                .overridingErrorMessage("Существующая запись не должна менять значение из-за нового дефолта")
                .isTrue();
    }

    @Test
    void newlyConstructedEntity_defaultsEmailToFalse() {
        // Java-дефолт для НОВЫХ объектов — то, что реально видит createDefaultSettings.
        var settings = new UserSettingsJpaEntity();

        assertThat(settings.isNotifyEmail()).isFalse();
        assertThat(settings.isNotifyTelegram()).isTrue();
        assertThat(settings.isNotifyPush()).isTrue();
    }
}
