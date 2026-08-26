package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ReminderRepository extends JpaRepository<ReminderJpaEntity, UUID> {

    // Пропуск не должен помечать задачу просроченной и не должен попадать
    // ни в какие счётчики невыполненного (Б4) — здесь только своя таблица,
    // никаких обновлений TaskJpaEntity.
    @Modifying
    @Query("UPDATE ReminderJpaEntity r SET r.status = ru.taskflow.task.infrastructure.persistence.ReminderStatus.CANCELLED "
            + "WHERE r.task.id = :taskId AND r.status = ru.taskflow.task.infrastructure.persistence.ReminderStatus.PENDING")
    int cancelPendingByTaskId(@Param("taskId") UUID taskId);
}
