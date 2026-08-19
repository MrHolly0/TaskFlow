package ru.taskflow.notify.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionJpaEntity, UUID> {

    Optional<PushSubscriptionJpaEntity> findByEndpoint(String endpoint);

    List<PushSubscriptionJpaEntity> findByUserId(UUID userId);
}
