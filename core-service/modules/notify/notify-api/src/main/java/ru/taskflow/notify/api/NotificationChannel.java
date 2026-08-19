package ru.taskflow.notify.api;

/**
 * Канал доставки напоминания. Отдельно от IdentityProvider: push не является
 * способом входа, но должен участвовать в той же очереди уведомлений.
 */
public enum NotificationChannel {
    TELEGRAM, EMAIL, WEB_PUSH
}
