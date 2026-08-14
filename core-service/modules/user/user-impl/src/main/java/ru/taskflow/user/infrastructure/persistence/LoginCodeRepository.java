package ru.taskflow.user.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface LoginCodeRepository extends JpaRepository<LoginCodeJpaEntity, UUID> {

    List<LoginCodeJpaEntity> findByEmailOrderByCreatedAtDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, OffsetDateTime after);
}
