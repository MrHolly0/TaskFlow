package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TelegramNotificationSenderTest {

    private static final String MOSCOW = "Europe/Moscow";
    private static final String YEKATERINBURG = "Asia/Yekaterinburg";

    private final TelegramNotificationSender sender =
            new TelegramNotificationSender(mock(RestClient.class), new ObjectMapper());

    @Test
    void buildMessageText_formatsTodayDeadline() {
        var deadline = ZonedDateTime.now(ZoneId.of(MOSCOW)).withHour(12).withMinute(0).withSecond(0).withNano(0);

        String text = sender.buildMessageText("доделать презентацию", deadline.toOffsetDateTime().toString(), MOSCOW);

        assertThat(text).contains("Дедлайн: сегодня в 12:00");
    }

    @Test
    void buildMessageText_formatsTomorrowDeadlineWithoutLeadingZeroInHour() {
        var deadline = ZonedDateTime.now(ZoneId.of(MOSCOW)).plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);

        String text = sender.buildMessageText("задача", deadline.toOffsetDateTime().toString(), MOSCOW);

        assertThat(text).contains("Дедлайн: завтра в 9:00");
    }

    @Test
    void buildMessageText_formatsFarDeadlineWithDateAndGenitiveMonth() {
        // Год далеко в будущем — чтобы дата гарантированно не совпала с "сегодня"/"завтра" на момент прогона теста.
        String text = sender.buildMessageText("задача", "2030-08-14T15:00:00+03:00", MOSCOW);

        assertThat(text).contains("Дедлайн: 14 августа в 15:00");
    }

    @Test
    void buildMessageText_omitsDeadlineLine_whenDeadlineIsNull() {
        String text = sender.buildMessageText("задача без срока", null, MOSCOW);

        assertThat(text).doesNotContain("Дедлайн");
    }

    @Test
    void buildMessageText_usesUserTimezoneNotDefault() {
        // 23:30 в Екатеринбурге (+5) — тот же день по зоне пользователя,
        // хотя по Москве (+3) это было бы уже за полночь следующих суток.
        var nowInUserZone = ZonedDateTime.now(ZoneId.of(YEKATERINBURG)).withHour(23).withMinute(30).withSecond(0).withNano(0);

        String text = sender.buildMessageText("задача", nowInUserZone.toOffsetDateTime().toString(), YEKATERINBURG);

        assertThat(text).contains("Дедлайн: сегодня в 23:30");
    }

    @Test
    void buildMessageText_fallsBackToDefaultZone_whenTimezoneMissing() {
        var deadline = ZonedDateTime.now(ZoneId.of(MOSCOW)).withHour(12).withMinute(0).withSecond(0).withNano(0);

        String text = sender.buildMessageText("задача", deadline.toOffsetDateTime().toString(), null);

        assertThat(text).contains("Дедлайн: сегодня в 12:00");
    }

    @Test
    void buildMessageText_includesTitle() {
        String text = sender.buildMessageText("купить молоко", null, MOSCOW);

        assertThat(text).contains("купить молоко");
    }
}
