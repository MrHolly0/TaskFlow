package ru.taskflow.task.infrastructure.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<TaskJpaEntity, UUID> {

    Optional<TaskJpaEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group g
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND (:groupId IS NULL OR t.group.id = :groupId)
              AND (:status IS NULL OR t.status = :status)
              AND (:priority IS NULL OR t.priority = :priority)
              AND (:tag IS NULL OR EXISTS (
                  SELECT 1 FROM t.tags tag WHERE tag.name = :tag
              ))
            """)
    Page<TaskJpaEntity> findAllWithFilter(
            @Param("userId") UUID userId,
            @Param("groupId") UUID groupId,
            @Param("status") TaskStatus status,
            @Param("priority") TaskPriority priority,
            @Param("tag") String tag,
            Pageable pageable
    );

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status != :done
              AND t.isDeleted = false
              AND (t.deadline IS NULL OR t.deadline <= :endOfToday)
            ORDER BY CASE t.priority
              WHEN 'URGENT' THEN 0
              WHEN 'HIGH' THEN 1
              WHEN 'NORMAL' THEN 2
              ELSE 3
            END,
            CASE WHEN t.deadline IS NULL THEN 1 ELSE 0 END,
            t.deadline
            """)
    List<TaskJpaEntity> findFocusTasks(
            @Param("userId") UUID userId,
            @Param("done") TaskStatus done,
            @Param("endOfToday") OffsetDateTime endOfToday
    );

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status != :done
              AND t.isDeleted = false
              AND t.deadline > :endOfToday
            ORDER BY CASE t.priority
              WHEN 'URGENT' THEN 0
              WHEN 'HIGH' THEN 1
              WHEN 'NORMAL' THEN 2
              ELSE 3
            END,
            t.deadline
            """)
    List<TaskJpaEntity> findUpcomingFocusTasks(
            @Param("userId") UUID userId,
            @Param("done") TaskStatus done,
            @Param("endOfToday") OffsetDateTime endOfToday
    );

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.status != :done
              AND t.isDeleted = false
              AND (DATE(t.deadline) = DATE(:date) OR (t.deadline IS NULL))
            ORDER BY CASE t.priority
              WHEN 'URGENT' THEN 0
              WHEN 'HIGH' THEN 1
              WHEN 'NORMAL' THEN 2
              ELSE 3
            END,
            t.createdAt DESC
            """)
    List<TaskJpaEntity> findDigestTasks(
            @Param("userId") UUID userId,
            @Param("date") OffsetDateTime date,
            @Param("done") TaskStatus done
    );

    @Query(value = """
            SELECT created_at, completed_at, deadline, status
            FROM tasks
            WHERE user_id = :userId
              AND (
                (is_deleted = false AND created_at >= :from)
                OR (completed_at IS NOT NULL AND completed_at >= :from)
                OR (
                  is_deleted = false
                  AND deadline IS NOT NULL
                  AND deadline < :now
                  AND status NOT IN ('DONE', 'CANCELLED')
                )
              )
            """, nativeQuery = true)
    List<Object[]> findStatsRows(
            @Param("userId") UUID userId,
            @Param("from") OffsetDateTime from,
            @Param("now") OffsetDateTime now
    );

    @Query("""
            SELECT t FROM TaskJpaEntity t
            WHERE t.userId = :userId
              AND t.status IN (ru.taskflow.task.api.TaskStatus.TODO, ru.taskflow.task.api.TaskStatus.IN_PROGRESS)
            ORDER BY
              CASE WHEN t.deadline IS NOT NULL AND t.deadline < :now THEN 0 ELSE 1 END ASC,
              CASE WHEN t.deadline IS NULL THEN 1 ELSE 0 END ASC,
              t.deadline ASC,
              t.createdAt DESC
            """)
    List<TaskJpaEntity> findAssistantContext(@Param("userId") UUID userId,
                                             @Param("now") OffsetDateTime now,
                                             Pageable pageable);

    @Query("""
            SELECT t FROM TaskJpaEntity t
            LEFT JOIN FETCH t.group
            LEFT JOIN FETCH t.tags
            WHERE t.userId = :userId
              AND t.isDeleted = false
              AND (:includeCompleted = true OR t.status NOT IN (ru.taskflow.task.api.TaskStatus.DONE, ru.taskflow.task.api.TaskStatus.CANCELLED))
              AND (LOWER(t.title) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR LOWER(t.description) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY t.updatedAt DESC
            """)
    List<TaskJpaEntity> search(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("includeCompleted") boolean includeCompleted,
            Pageable pageable
    );

    @Modifying
    @Query(value = """
            UPDATE tasks
            SET is_deleted = true, deleted_at = NOW()
            WHERE user_id = :userId
              AND status IN ('DONE', 'CANCELLED')
              AND is_deleted = false
            """, nativeQuery = true)
    int softDeleteAllCompletedByUser(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            UPDATE tasks
            SET is_deleted = true, deleted_at = NOW()
            WHERE user_id = :userId
              AND status IN ('DONE', 'CANCELLED')
              AND is_deleted = false
              AND completed_at < :before
            """, nativeQuery = true)
    int softDeleteCompletedBefore(@Param("userId") UUID userId, @Param("before") OffsetDateTime before);

    @Modifying
    @Query(value = """
            DELETE FROM tasks
            WHERE status IN ('DONE', 'CANCELLED')
              AND completed_at < :before
            """, nativeQuery = true)
    int physicalDeleteCompletedBefore(@Param("before") OffsetDateTime before);

    @Modifying
    @Query(value = "UPDATE tasks SET group_id = NULL WHERE group_id = :groupId", nativeQuery = true)
    void detachGroup(@Param("groupId") UUID groupId);

}
