package ru.taskflow.user.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.user.api.IdentityProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserIdentityRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(basePackageClasses = UserIdentityRepository.class)
    @EntityScan(basePackageClasses = UserIdentityJpaEntity.class)
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
    private UserIdentityRepository identityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByProviderAndExternalId_findsSavedIdentity() {
        var user = userRepository.saveAndFlush(newUser());
        identityRepository.saveAndFlush(newIdentity(user, IdentityProvider.TELEGRAM, "1"));

        var found = identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "1");

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(user.getId());
    }

    @Test
    void save_rejectsDuplicateProviderAndExternalIdPair() {
        var user = userRepository.saveAndFlush(newUser());
        identityRepository.saveAndFlush(newIdentity(user, IdentityProvider.TELEGRAM, "2"));

        assertThatThrownBy(() ->
                identityRepository.saveAndFlush(newIdentity(user, IdentityProvider.TELEGRAM, "2")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUser_cascadesToIdentities() {
        var user = userRepository.saveAndFlush(newUser());
        identityRepository.saveAndFlush(newIdentity(user, IdentityProvider.TELEGRAM, "3"));

        userRepository.delete(user);
        userRepository.flush();
        entityManager.clear();

        assertThat(identityRepository.findByProviderAndExternalId(IdentityProvider.TELEGRAM, "3")).isEmpty();
    }

    private UserJpaEntity newUser() {
        return new UserJpaEntity();
    }

    private UserIdentityJpaEntity newIdentity(UserJpaEntity user, IdentityProvider provider, String externalId) {
        var identity = new UserIdentityJpaEntity();
        identity.setUser(user);
        identity.setProvider(provider);
        identity.setExternalId(externalId);
        return identity;
    }
}
