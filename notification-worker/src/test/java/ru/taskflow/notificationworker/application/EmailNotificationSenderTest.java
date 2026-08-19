package ru.taskflow.notificationworker.application;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailNotificationSenderTest {

    private static final String DESTINATION = "user@example.com";
    private static final String FROM = "noreply@taskflow.example";
    private static final String MINIAPP_URL = "https://app.taskflow.example";

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private Environment environment;

    @Test
    void supports_onlyEmailChannel() {
        var sender = new EmailNotificationSender(mailSender, environment, "smtp.example.com", FROM, MINIAPP_URL);

        assertThat(sender.supports("EMAIL")).isTrue();
        assertThat(sender.supports("TELEGRAM")).isFalse();
    }

    @Test
    void plainText_includesTitleDeadlineAndBothLinks() {
        var sender = new EmailNotificationSender(mailSender, environment, "smtp.example.com", FROM, MINIAPP_URL);

        String text = sender.plainText("купить молоко", "2030-01-01T10:00:00+03:00", "Europe/Moscow");

        assertThat(text).contains("купить молоко");
        assertThat(text).contains("1 января в 10:00");
        assertThat(text).contains(MINIAPP_URL + "/all");
        assertThat(text).contains(MINIAPP_URL + "/settings");
    }

    @Test
    void plainText_omitsDeadlineLine_whenDeadlineIsNull() {
        var sender = new EmailNotificationSender(mailSender, environment, "smtp.example.com", FROM, MINIAPP_URL);

        String text = sender.plainText("задача без срока", null, "Europe/Moscow");

        assertThat(text).doesNotContain("Срок");
    }

    @Test
    void html_escapesTitleAndIncludesBothLinksAsAnchors() {
        var sender = new EmailNotificationSender(mailSender, environment, "smtp.example.com", FROM, MINIAPP_URL);

        String html = sender.html("<script>alert(1)</script>", "2030-01-01T10:00:00+03:00", "Europe/Moscow");

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("href=\"" + MINIAPP_URL + "/all\"");
        assertThat(html).contains("href=\"" + MINIAPP_URL + "/settings\"");
    }

    @Test
    void sendTaskReminder_smtpConfigured_sendsMimeMessageWithSubjectFromAndDestination() throws Exception {
        var sender = new EmailNotificationSender(mailSender, environment, "smtp.example.com", FROM, MINIAPP_URL);
        MimeMessage mimeMessage = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        sender.sendTaskReminder(DESTINATION, "купить молоко", "2030-01-01T10:00:00+03:00", "Europe/Moscow");

        verify(mailSender).send(mimeMessage);
        assertThat(mimeMessage.getSubject()).isEqualTo("купить молоко");
        assertThat(mimeMessage.getAllRecipients()).extracting(Object::toString).containsExactly(DESTINATION);
        assertThat(mimeMessage.getFrom()).extracting(Object::toString).containsExactly(FROM);
    }

    @Test
    void sendTaskReminder_smtpConfiguredButFromMissing_throwsWithoutSending() {
        var noFromSender = new EmailNotificationSender(mailSender, environment, "smtp.example.com", "", MINIAPP_URL);

        assertThatThrownBy(() -> noFromSender.sendTaskReminder(DESTINATION, "задача", null, "Europe/Moscow"))
                .isInstanceOf(IllegalStateException.class);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendTaskReminder_smtpNotConfiguredOutsideDev_throwsWithoutSending() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(false);
        var noSmtpSender = new EmailNotificationSender(mailSender, environment, "", FROM, MINIAPP_URL);

        assertThatThrownBy(() -> noSmtpSender.sendTaskReminder(DESTINATION, "задача", null, "Europe/Moscow"))
                .isInstanceOf(IllegalStateException.class);

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendTaskReminder_smtpNotConfiguredInDev_logsWithoutSendingOrThrowing() {
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        var devSender = new EmailNotificationSender(mailSender, environment, "", FROM, MINIAPP_URL);

        devSender.sendTaskReminder(DESTINATION, "задача", null, "Europe/Moscow");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}
