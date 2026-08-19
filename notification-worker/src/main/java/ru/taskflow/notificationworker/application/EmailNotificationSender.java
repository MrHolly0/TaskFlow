package ru.taskflow.notificationworker.application;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * SMTP сейчас — личный почтовый ящик Яндекса с лимитом около 500 писем в
 * сутки. На нынешнем числе пользователей запаса хватает; при росте нужен
 * отдельный сервис рассылки. Если письма вдруг перестанут уходить и в логе
 * SMTP-ошибка про превышение лимита — это она, не баг.
 */
@Service
@Slf4j
public class EmailNotificationSender implements NotificationSender {

    private final JavaMailSender mailSender;
    private final Environment environment;
    private final String mailHost;
    private final String mailFrom;
    private final String miniappUrl;

    public EmailNotificationSender(
            JavaMailSender mailSender,
            Environment environment,
            @Value("${spring.mail.host:}") String mailHost,
            @Value("${app.mail.from:}") String mailFrom,
            @Value("${app.frontend.miniapp-url:}") String miniappUrl
    ) {
        this.mailSender = mailSender;
        this.environment = environment;
        this.mailHost = mailHost;
        this.mailFrom = mailFrom;
        this.miniappUrl = miniappUrl;
    }

    @Override
    public boolean supports(String channel) {
        return "EMAIL".equals(channel);
    }

    @Override
    public void sendTaskReminder(String destination, String title, String deadline, String timezone) {
        if (mailHost.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("dev"))) {
                log.info("SMTP не настроен (профиль dev), письмо-напоминание для {}: {}", destination, title);
                return;
            }
            throw new IllegalStateException("SMTP не настроен");
        }
        if (mailFrom.isBlank()) {
            throw new IllegalStateException("SMTP_FROM не настроен");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailFrom);
            helper.setTo(destination);
            helper.setSubject(title);
            helper.setText(plainText(title, deadline, timezone), html(title, deadline, timezone));
            mailSender.send(message);
            log.info("Sent notification email to {}", destination);
        } catch (Exception e) {
            log.error("Failed to send notification email to {}: {}", destination, e.getMessage());
            throw new IllegalStateException("Не удалось отправить письмо-напоминание", e);
        }
    }

    private String deadlineLine(String deadline, String timezone) {
        String humanized = DeadlineHumanizer.humanize(deadline, timezone);
        return humanized == null ? "" : "Срок: " + humanized + "\n";
    }

    private String taskLink() {
        return miniappUrl.isBlank() ? "" : miniappUrl + "/all";
    }

    private String settingsLink() {
        return miniappUrl.isBlank() ? "" : miniappUrl + "/settings";
    }

    /**
     * Собирает текст письма. Пакетная видимость для тестов — не требует
     * поднятия JavaMailSender ради проверки чистой логики форматирования
     * (см. TelegramNotificationSender.buildMessageText).
     */
    String plainText(String title, String deadline, String timezone) {
        StringBuilder text = new StringBuilder();
        text.append("Напоминание о задаче: ").append(title).append("\n\n");
        text.append(deadlineLine(deadline, timezone));
        String taskLink = taskLink();
        if (!taskLink.isBlank()) {
            text.append("Открыть: ").append(taskLink).append("\n");
        }
        text.append("\n---\n");
        String settingsLink = settingsLink();
        if (!settingsLink.isBlank()) {
            text.append("Настроить уведомления: ").append(settingsLink);
        }
        return text.toString();
    }

    String html(String title, String deadline, String timezone) {
        StringBuilder html = new StringBuilder();
        html.append("<p>Напоминание о задаче: <b>").append(escape(title)).append("</b></p>");
        String humanizedDeadline = DeadlineHumanizer.humanize(deadline, timezone);
        if (humanizedDeadline != null) {
            html.append("<p>Срок: ").append(escape(humanizedDeadline)).append("</p>");
        }
        String taskLink = taskLink();
        if (!taskLink.isBlank()) {
            html.append("<p><a href=\"").append(taskLink).append("\">Открыть в TaskFlow</a></p>");
        }
        String settingsLink = settingsLink();
        if (!settingsLink.isBlank()) {
            html.append("<hr><p style=\"font-size:12px;color:#888\">")
                    .append("<a href=\"").append(settingsLink).append("\">Настроить уведомления</a></p>");
        }
        return html.toString();
    }

    private String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
