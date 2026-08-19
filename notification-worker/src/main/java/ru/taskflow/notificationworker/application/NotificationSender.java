package ru.taskflow.notificationworker.application;

public interface NotificationSender {

    boolean supports(String channel);

    void sendTaskReminder(String destination, String title, String deadline, String timezone);
}
