package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<TagJpaEntity, UUID> {

    Optional<TagJpaEntity> findByUserIdAndName(UUID userId, String name);

    List<TagJpaEntity> findAllByUserIdAndNameIn(UUID userId, List<String> names);

    @Modifying
    @Query("UPDATE TagJpaEntity t SET t.userId = :to WHERE t.userId = :from")
    int reassignOwner(@Param("from") UUID from, @Param("to") UUID to);
}
