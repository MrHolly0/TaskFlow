package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailSenderTest {

    private static final String EMAIL = "user@example.com";
    private static final String CODE = "123456";

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private Environment environment;

    @Test
    void sendLoginCode_smtpConfigured_sendsEmailContainingCode() {
        var sender = new EmailSender(mailSender, environment, "smtp.example.com");

        sender.sendLoginCode(EMAIL, CODE);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly(EMAIL);
        assertThat(captor.getValue().getText()).contains(CODE);
    }

    @Test
    void sendLoginCode_smtpNotConfiguredOutsideDev_throwsWithoutSending() {
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
        var sender = new EmailSender(mailSender, environment, "");

        assertThatThrownBy(() -> sender.sendLoginCode(EMAIL, CODE))
                .isInstanceOf(IllegalStateException.class);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendLoginCode_smtpNotConfiguredInDev_logsWithoutSendingOrThrowing() {
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(true);
        var sender = new EmailSender(mailSender, environment, "");

        sender.sendLoginCode(EMAIL, CODE);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
