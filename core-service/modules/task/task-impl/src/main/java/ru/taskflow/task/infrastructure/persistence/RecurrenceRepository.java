package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * task_id — сам первичный ключ recurrences (одно правило на задачу), поэтому
 * findById/findAllById из JpaRepository уже дают точечное и пакетное чтение
 * по task_id без отдельных производных запросов.
 */
public interface RecurrenceRepository extends JpaRepository<RecurrenceJpaEntity, UUID> {
}
