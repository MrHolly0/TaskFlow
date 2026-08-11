package ru.taskflow.assistant.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProposalRepository extends JpaRepository<ProposalJpaEntity, UUID> {

    Optional<ProposalJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<ProposalJpaEntity> findByShortCodeAndUserId(String shortCode, UUID userId);

    @Query("""
            SELECT DISTINCT p FROM ProposalJpaEntity p
            LEFT JOIN FETCH p.actions
            WHERE p.id = :id AND p.userId = :userId
            """)
    Optional<ProposalJpaEntity> findWithActions(@Param("id") UUID id, @Param("userId") UUID userId);
}
