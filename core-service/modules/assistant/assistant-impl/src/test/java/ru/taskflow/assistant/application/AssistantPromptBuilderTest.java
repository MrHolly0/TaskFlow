package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPromptBuilderTest {

    // отличается от системной зоны машины (Europe/Moscow), чтобы тест не мог случайно
    // совпасть с системным умолчанием и замаскировать игнорирование ZoneId; без DST — детерминированно.
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private final AssistantPromptBuilder builder = new AssistantPromptBuilder();

    @Test
    void build_includesCurrentDate() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE);

        OffsetDateTime now = OffsetDateTime.now(ZONE);
        String expectedDate = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String expectedWeekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.of("ru"));

        assertThat(parts.systemPrompt()).contains(expectedDate);
        assertThat(parts.systemPrompt()).contains(expectedWeekday);
    }

    @Test
    void build_includesTimezoneOffset() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE);

        String expectedOffset = OffsetDateTime.now(ZONE).getOffset().getId();

        assertThat(parts.systemPrompt()).contains(expectedOffset);
    }

    @Test
    void build_includesWindow() {
        var window = windowWith(
                "T1 · купить корм коту · Покупки · без срока · MEDIUM",
                "T2 · позвонить марку · Работа · до 15.08 18:00 · HIGH"
        );

        var parts = builder.build(window, "любой текст", ZONE);

        assertThat(parts.systemPrompt()).contains("T1 · купить корм коту · Покупки · без срока · MEDIUM");
        assertThat(parts.systemPrompt()).contains("T2 · позвонить марку · Работа · до 15.08 18:00 · HIGH");
    }

    @Test
    void build_forbidsUnfoundedActions() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE);

        assertThat(parts.systemPrompt())
                .contains("Предлагай только те действия, которые прямо следуют из сказанного");
        assertThat(parts.systemPrompt())
                .contains("Если в реплике нет оснований для действия — не предлагай его");
    }

    @Test
    void build_requiresDuplicateCheckBeforeCreate() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE);

        assertThat(parts.systemPrompt()).contains("create_task");
        assertThat(parts.systemPrompt())
                .contains("сверься со списком выше: если там уже есть задача с тем же смыслом");
        assertThat(parts.systemPrompt())
                .contains("работай с найденной через её ярлык");
    }

    @Test
    void build_wrapsUserTextInDelimiters() {
        String userText = "сходил в магазин, взял молоко и хлеб";

        var parts = builder.build(emptyWindow(), userText, ZONE);

        assertThat(parts.systemPrompt()).doesNotContain(userText);

        String message = parts.userMessage();
        int start = message.indexOf(AssistantPromptBuilder.USER_TEXT_START);
        int textIndex = message.indexOf(userText);
        int end = message.indexOf(AssistantPromptBuilder.USER_TEXT_END);

        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(textIndex);
        assertThat(textIndex).isGreaterThan(start);
    }

    @Test
    void build_containsNoHardcodedPastDates() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE);

        assertThat(parts.systemPrompt()).doesNotContain("2026-04-25");
        assertThat(parts.userMessage()).doesNotContain("2026-04-25");
    }

    @Test
    void build_handlesEmptyWindow() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE);

        assertThat(parts.systemPrompt()).isNotBlank();
        assertThat(parts.systemPrompt()).contains("активных задач нет");
    }

    @Test
    void build_noLongerMentionsAskUser() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE);

        assertThat(parts.systemPrompt()).doesNotContain("ask_user");
    }

    private TaskContextWindow emptyWindow() {
        return new TaskContextWindow("", Map.of(), Map.of());
    }

    private TaskContextWindow windowWith(String... lines) {
        Map<String, UUID> refs = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String ref = "T" + (i + 1);
            refs.put(ref, UUID.randomUUID());
            titles.put(ref, lines[i]);
        }
        return new TaskContextWindow(String.join("\n", lines), refs, titles);
    }
}
