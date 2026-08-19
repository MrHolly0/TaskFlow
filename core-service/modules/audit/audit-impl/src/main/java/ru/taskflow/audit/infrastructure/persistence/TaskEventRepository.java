package ru.taskflow.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TaskEventRepository extends JpaRepository<TaskEventJpaEntity, UUID> {
    List<TaskEventJpaEntity> findByTaskIdOrderByOccurredAtDesc(UUID taskId);

    @Modifying
    @Query("UPDATE TaskEventJpaEntity e SET e.userId = :to WHERE e.userId = :from")
    int reassignOwner(@Param("from") UUID from, @Param("to") UUID to);
}
