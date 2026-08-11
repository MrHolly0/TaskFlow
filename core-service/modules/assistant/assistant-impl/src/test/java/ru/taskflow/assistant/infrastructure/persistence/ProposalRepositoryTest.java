package ru.taskflow.assistant.infrastructure.persistence;

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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ProposalRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = ProposalRepository.class)
    @EntityScan(basePackageClasses = ProposalJpaEntity.class)
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
    private ProposalRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void save_persistsProposalWithActions() {
        var proposal = newProposal();
        proposal.addAction(newAction(1, "CREATE", "Создать — купить молоко"));
        proposal.addAction(newAction(2, "COMPLETE", "Закрыть — позвонить Марку"));

        var saved = repository.saveAndFlush(proposal);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getActions()).hasSize(2);
    }

    @Test
    void findWithActions_loadsActionsInOrdinalOrder() {
        var proposal = newProposal();
        proposal.addAction(newAction(2, "COMPLETE", "второе"));
        proposal.addAction(newAction(1, "CREATE", "первое"));
        var id = repository.saveAndFlush(proposal).getId();
        entityManager.clear();

        var found = repository.findWithActions(id, proposal.getUserId()).orElseThrow();

        assertThat(found.getActions()).extracting(ProposalActionJpaEntity::getSummary)
                .containsExactly("первое", "второе");
    }

    @Test
    void findByShortCodeAndUserId_findsProposal() {
        var proposal = newProposal();
        repository.saveAndFlush(proposal);

        var found = repository.findByShortCodeAndUserId(proposal.getShortCode(), proposal.getUserId());

        assertThat(found).isPresent();
    }

    @Test
    void findByIdAndUserId_ignoresForeignUser() {
        var proposal = newProposal();
        var id = repository.saveAndFlush(proposal).getId();

        var found = repository.findByIdAndUserId(id, UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    private ProposalJpaEntity newProposal() {
        var p = new ProposalJpaEntity();
        p.setShortCode("ABC23456");
        p.setUserId(UUID.randomUUID());
        p.setSourceChannel("TELEGRAM");
        p.setInputKind("TEXT");
        p.setSourceText("в магазин сходил");
        p.setStatus("PENDING");
        p.setCreatedAt(OffsetDateTime.now());
        p.setExpiresAt(OffsetDateTime.now().plusHours(24));
        return p;
    }

    private ProposalActionJpaEntity newAction(int ordinal, String type, String summary) {
        var a = new ProposalActionJpaEntity();
        a.setOrdinal(ordinal);
        a.setType(type);
        a.setSummary(summary);
        a.setAccepted(true);
        return a;
    }
}
