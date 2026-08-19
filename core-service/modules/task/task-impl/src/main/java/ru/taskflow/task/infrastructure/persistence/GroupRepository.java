package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GroupRepository extends JpaRepository<GroupJpaEntity, UUID> {

    Optional<GroupJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    Optional<GroupJpaEntity> findByUserIdAndName(UUID userId, String name);

    List<GroupJpaEntity> findAllByUserId(UUID userId);

    @Modifying
    @Query("UPDATE GroupJpaEntity g SET g.userId = :to WHERE g.userId = :from")
    int reassignOwner(@Param("from") UUID from, @Param("to") UUID to);

    long countByUserId(UUID userId);
}
