package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.taskflow.user.api.IdentityProvider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface LoginCodeRepository extends JpaRepository<LoginCodeJpaEntity, UUID> {

    List<LoginCodeJpaEntity> findByChannelAndIdentifierOrderByCreatedAtDesc(IdentityProvider channel, String identifier);

    long countByChannelAndIdentifierAndCreatedAtAfter(IdentityProvider channel, String identifier, OffsetDateTime after);
}
