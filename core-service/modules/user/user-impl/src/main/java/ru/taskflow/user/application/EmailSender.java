package ru.taskflow.user.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Если SMTP не настроен, письмо не уходит, а код пишется в лог — иначе
 * локальная разработка невозможна. Это допустимо только под профилем dev:
 * в остальных случаях ненастроенный SMTP обязан быть ошибкой, а не тихим
 * переходом в режим, где код виден в логах.
 */
@Component
@Slf4j
public class EmailSender {

    private final JavaMailSender mailSender;
    private final Environment environment;
    private final String mailHost;

    public EmailSender(
            JavaMailSender mailSender,
            Environment environment,
            @Value("${spring.mail.host:}") String mailHost
    ) {
        this.mailSender = mailSender;
        this.environment = environment;
        this.mailHost = mailHost;
    }

    public void sendLoginCode(String email, String code) {
        if (mailHost.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("dev"))) {
                log.info("SMTP не настроен (профиль dev), код входа для {}: {}", email, code);
                return;
            }
            throw new IllegalStateException("SMTP не настроен");
        }

        var message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("Код входа в TaskFlow");
        message.setText("Код входа: " + code
                + "\nДействует 10 минут."
                + "\nЕсли это не вы — просто не вводите код.");
        mailSender.send(message);
    }
}
