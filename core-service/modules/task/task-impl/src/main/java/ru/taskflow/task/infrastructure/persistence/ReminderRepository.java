package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<ReminderJpaEntity, UUID> {

    // Пропуск не должен помечать задачу просроченной и не должен попадать
    // ни в какие счётчики невыполненного (Б4) — здесь только своя таблица,
    // никаких обновлений TaskJpaEntity.
    @Modifying
    @Query("UPDATE ReminderJpaEntity r SET r.status = ru.taskflow.task.infrastructure.persistence.ReminderStatus.CANCELLED "
            + "WHERE r.task.id = :taskId AND r.status = ru.taskflow.task.infrastructure.persistence.ReminderStatus.PENDING")
    int cancelPendingByTaskId(@Param("taskId") UUID taskId);

    // Б3: кандидаты на автоматический шаг цепочки — время настало, решения
    // не было (иначе статус уже не PENDING: снят вручную — SNOOZED, задача
    // закрыта — CANCELLED).
    List<ReminderJpaEntity> findByStatusAndChainStepIsNotNullAndFireAtBefore(ReminderStatus status, OffsetDateTime before);

    // А1: для карточки одной задачи.
    List<ReminderJpaEntity> findByTaskIdAndStatusOrderByFireAtAsc(UUID taskId, ReminderStatus status);

    // А3: одна выборка на всю страницу списка задач, не запрос на карточку.
    List<ReminderJpaEntity> findByTaskIdInAndStatusOrderByFireAtAsc(List<UUID> taskIds, ReminderStatus status);

    // А2: снятие адресуется id самого напоминания, принадлежность задаче
    // проверяется тем же условием, что и её видимость пользователю.
    Optional<ReminderJpaEntity> findByIdAndTaskId(UUID id, UUID taskId);
}
