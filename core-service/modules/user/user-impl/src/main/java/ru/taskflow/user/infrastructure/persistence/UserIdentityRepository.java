package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.taskflow.user.api.IdentityProvider;

import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentityJpaEntity, UUID> {

    Optional<UserIdentityJpaEntity> findByProviderAndExternalId(IdentityProvider provider, String externalId);
}
