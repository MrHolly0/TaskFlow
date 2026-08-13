package ru.taskflow.notificationworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramNotificationSender {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${app.telegram.bot-token:}")
    private String botToken;

    @Value("${app.telegram.api-base-url:https://api.telegram.org}")
    private String apiBaseUrl;

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM", new Locale("ru"));

    public void sendTaskReminder(Long chatId, String title, String deadline, String timezone) {
        String text = buildMessageText(title, deadline, timezone);

        try {
            sendMessage(chatId, text);
            log.info("Sent notification to chat {}", chatId);
        } catch (RestClientException e) {
            log.error("Failed to send notification to chat {}: {}", chatId, e.getMessage());
            throw e;
        }
    }

    /**
     * Собирает текст напоминания. Отдельная точка входа для тестов —
     * не требует поднятия RestClient ради проверки чистой логики форматирования.
     */
    String buildMessageText(String title, String deadline, String timezone) {
        return "📌 Напоминание о задаче\n\n<b>" + title + "</b>" + deadlineLine(deadline, timezone);
    }

    private String deadlineLine(String iso, String timezone) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        ZoneId zone = resolveZone(timezone);
        try {
            ZonedDateTime local = OffsetDateTime.parse(iso).atZoneSameInstant(zone);
            return "\nДедлайн: " + humanize(local, zone);
        } catch (Exception e) {
            return "\nДедлайн: " + iso;
        }
    }

    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    private String humanize(ZonedDateTime local, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        LocalDate date = local.toLocalDate();
        String time = TIME_FMT.format(local);

        if (date.isEqual(today)) {
            return "сегодня в " + time;
        }
        if (date.isEqual(today.plusDays(1))) {
            return "завтра в " + time;
        }
        return DATE_FMT.format(local) + " в " + time;
    }

    private void sendMessage(Long chatId, String text) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("chat_id", chatId);
        payload.put("text", text);
        payload.put("parse_mode", "HTML");

        String url = apiBaseUrl + "/bot" + botToken + "/sendMessage";

        restClient.post()
            .uri(url)
            .body(payload)
            .retrieve()
            .toEntity(String.class);
    }
}
