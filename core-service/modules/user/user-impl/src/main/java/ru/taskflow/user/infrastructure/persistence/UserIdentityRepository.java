package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.taskflow.user.api.IdentityProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentityJpaEntity, UUID> {

    Optional<UserIdentityJpaEntity> findByProviderAndExternalId(IdentityProvider provider, String externalId);

    Optional<UserIdentityJpaEntity> findByUser_IdAndProvider(UUID userId, IdentityProvider provider);

    List<UserIdentityJpaEntity> findByUser_Id(UUID userId);

    long countByUser_Id(UUID userId);
}
