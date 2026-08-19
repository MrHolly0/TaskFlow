package ru.taskflow.notify.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ScheduledNotificationRepository extends JpaRepository<ScheduledNotificationJpaEntity, UUID> {

    @Modifying
    @Query("DELETE FROM ScheduledNotificationJpaEntity s WHERE s.taskId = :taskId AND s.sent = false")
    void deleteUnsentByTaskId(UUID taskId);

    // telegram_chat_id вместе с user_id в одном UPDATE: если делать это двумя
    // отдельными запросами, после первого перенесённые строки станут
    // неотличимы (по user_id) от тех, что у target были изначально, и второй
    // запрос задел бы чужие уведомления тоже.
    @Modifying
    @Query("UPDATE ScheduledNotificationJpaEntity s SET s.userId = :to, s.telegramChatId = :chatId WHERE s.userId = :from")
    int reassignOwner(@Param("from") UUID from, @Param("to") UUID to, @Param("chatId") long chatId);

    // У target нет своего Telegram — некуда переставить chat_id. Оставляем
    // прежний: доставится в старый чат, пока пользователь не привяжет Telegram
    // на новой учётке, что честнее, чем NOT NULL-столбец с выдуманным значением.
    @Modifying
    @Query("UPDATE ScheduledNotificationJpaEntity s SET s.userId = :to WHERE s.userId = :from")
    int reassignOwnerKeepChatId(@Param("from") UUID from, @Param("to") UUID to);
}
