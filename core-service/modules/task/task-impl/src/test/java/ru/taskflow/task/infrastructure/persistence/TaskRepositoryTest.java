package ru.taskflow.task.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.task.api.TaskStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TaskRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = TaskRepository.class)
    @EntityScan(basePackageClasses = TaskJpaEntity.class)
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
    private TaskRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void search_findsMatchInDescription() {
        var userId = UUID.randomUUID();
        var task = newTask(userId, "покупки", "купить корм коту");
        repository.saveAndFlush(task);
        entityManager.clear();

        var found = repository.search(userId, "корм", false, PageRequest.of(0, 20));

        assertThat(found).extracting(TaskJpaEntity::getTitle).containsExactly("покупки");
    }

    @Test
    void search_excludesDoneTaskUnlessIncludeCompleted() {
        var userId = UUID.randomUUID();
        var task = newTask(userId, "позвонить Марку", "договориться о встрече");
        task.setStatus(TaskStatus.DONE);
        repository.saveAndFlush(task);
        entityManager.clear();

        var withoutCompleted = repository.search(userId, "Марку", false, PageRequest.of(0, 20));
        var withCompleted = repository.search(userId, "Марку", true, PageRequest.of(0, 20));

        assertThat(withoutCompleted).isEmpty();
        assertThat(withCompleted).extracting(TaskJpaEntity::getTitle).containsExactly("позвонить Марку");
    }

    private TaskJpaEntity newTask(UUID userId, String title, String description) {
        var task = new TaskJpaEntity();
        task.setUserId(userId);
        task.setTitle(title);
        task.setDescription(description);
        return task;
    }
}
