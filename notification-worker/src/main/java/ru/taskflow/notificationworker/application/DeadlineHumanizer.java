package ru.taskflow.notificationworker.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Превращает ISO-дедлайн в разговорную фразу («сегодня в 12:00», «завтра в
 * 9:00», «14 августа в 15:00») в часовом поясе пользователя. Общее для
 * Telegram- и email-напоминаний — оба показывают один и тот же срок.
 */
final class DeadlineHumanizer {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM", Locale.of("ru"));

    private DeadlineHumanizer() {
    }

    static String humanize(String iso, String timezone) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        ZoneId zone = resolveZone(timezone);
        try {
            ZonedDateTime local = OffsetDateTime.parse(iso).atZoneSameInstant(zone);
            return humanize(local, zone);
        } catch (Exception e) {
            return iso;
        }
    }

    private static ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    private static String humanize(ZonedDateTime local, ZoneId zone) {
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
}
