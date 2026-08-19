package ru.taskflow.notificationworker.application;

/**
 * Push-служба ответила 404/410 (или строка подписки уже исчезла) — подписка
 * протухла окончательно. Это не сбой для повтора, а сигнал удалить строку.
 */
public class PushSubscriptionGoneException extends RuntimeException {
    public PushSubscriptionGoneException(String subscriptionId) {
        super("Подписка недействительна: " + subscriptionId);
    }
}
