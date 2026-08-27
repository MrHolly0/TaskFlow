package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPromptBuilderTest {

    // отличается от системной зоны машины (Europe/Moscow), чтобы тест не мог случайно
    // совпасть с системным умолчанием и замаскировать игнорирование ZoneId; без DST — детерминированно.
    private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private final AssistantPromptBuilder builder = new AssistantPromptBuilder("Мунин");

    @Test
    void build_includesCurrentDateAndTime() {
        // Только дата ломала все сроки короче суток — «через час» и «сегодня
        // вечером» неразрешимы без времени суток (Task 0, живой дефект).
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        OffsetDateTime now = OffsetDateTime.now(ZONE);
        String expectedDateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String expectedWeekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.of("ru"));

        assertThat(parts.systemPrompt()).contains(expectedDateTime);
        assertThat(parts.systemPrompt()).contains(expectedWeekday);
    }

    @Test
    void build_labelsCurrentValueAsDateAndTimeNotJustDate() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).contains("Текущие дата и время");
        assertThat(parts.systemPrompt()).doesNotContain("Текущая дата:");
    }

    @Test
    void build_includesTimezoneOffset() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        String expectedOffset = OffsetDateTime.now(ZONE).getOffset().getId();

        assertThat(parts.systemPrompt()).contains(expectedOffset);
    }

    @Test
    void build_includesWindow() {
        var window = windowWith(
                "T1 · купить корм коту · Покупки · без срока · MEDIUM",
                "T2 · позвонить марку · Работа · до 15.08 18:00 · HIGH"
        );

        var parts = builder.build(window, "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).contains("T1 · купить корм коту · Покупки · без срока · MEDIUM");
        assertThat(parts.systemPrompt()).contains("T2 · позвонить марку · Работа · до 15.08 18:00 · HIGH");
    }

    @Test
    void build_forbidsUnfoundedActions() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt())
                .contains("Предлагай только те действия, которые прямо следуют из сказанного");
        assertThat(parts.systemPrompt())
                .contains("Если в реплике нет оснований для действия — не предлагай его");
    }

    @Test
    void build_requiresDuplicateCheckBeforeCreate() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).contains("type=create");
        assertThat(parts.systemPrompt())
                .contains("сверься со списком выше: если там уже есть задача с тем же смыслом");
        assertThat(parts.systemPrompt())
                .contains("работай с найденной через её ярлык");
    }

    // Живой дефект: «сходил в кино» при активной «кино с настей» создавало
    // новую задачу вместо закрытия существующей — правило про инфинитив/
    // повелительное наклонение не оговаривало прошедшее время, и модель
    // подвела его под то же правило.
    @Test
    void build_distinguishesPastTenseFromNewTaskCommand() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt())
                .contains("Прошедшее время — не это");
        assertThat(parts.systemPrompt())
                .contains("сообщение о сделанном, а не название новой задачи");
        assertThat(parts.systemPrompt())
                .contains("закрывай её (действие с type=complete), а не создавай вторую");
    }

    @Test
    void build_instructsToFillDescriptionOnlyWithExtraDetails() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt())
                .contains("Заполняй description у действия, только если в реплике есть подробности сверх");
        assertThat(parts.systemPrompt())
                .contains("оставляй description пустым");
    }

    @Test
    void build_wrapsUserTextInDelimiters() {
        String userText = "сходил в магазин, взял молоко и хлеб";

        var parts = builder.build(emptyWindow(), userText, ZONE, noGroups());

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
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).doesNotContain("2026-04-25");
        assertThat(parts.userMessage()).doesNotContain("2026-04-25");
    }

    @Test
    void build_handlesEmptyWindow() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).isNotBlank();
        assertThat(parts.systemPrompt()).contains("активных задач нет");
    }

    @Test
    void build_noLongerMentionsAskUser() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).doesNotContain("ask_user");
    }

    @Test
    void build_noLongerMentionsSeparateToolsPerActionType() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).doesNotContain("create_tasks(");
        assertThat(parts.systemPrompt()).doesNotContain("complete_task(");
        assertThat(parts.systemPrompt()).doesNotContain("mark_ambiguous");
    }

    @Test
    void build_explainsAmbiguousReasonField() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).contains("ambiguous_reason");
        assertThat(parts.systemPrompt()).contains("propose_actions");
    }

    @Test
    void build_addsCreateFirstHintOnlyForQuickAdd() {
        var chat = builder.build(emptyWindow(), "любой текст", ZONE,
                ru.taskflow.assistant.api.AssistantEntryPoint.CHAT, noGroups());
        var quickAdd = builder.build(emptyWindow(), "любой текст", ZONE,
                ru.taskflow.assistant.api.AssistantEntryPoint.QUICK_ADD, noGroups());

        assertThat(chat.systemPrompt()).doesNotContain("быстрого добавления");
        assertThat(quickAdd.systemPrompt()).contains("быстрого добавления");
        assertThat(quickAdd.systemPrompt()).contains("type=create первым");
    }

    @Test
    void build_includesConfiguredBrandName() {
        // Плейсхолдер бренда — первый в шаблоне, добавлен восьмым позиционным
        // аргументом formatted(): проверяем, что он не съехал на место даты
        // или другого поля при правке шаблона.
        var brandedBuilder = new AssistantPromptBuilder("Кракен");

        var parts = brandedBuilder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).contains("ассистент трекера задач Кракен");
    }

    @Test
    void build_defaultOverloadUsesChat() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).doesNotContain("быстрого добавления");
    }

    // Живой дефект: create_tasks описывал group как «название группы», не
    // сообщая модели, какие группы вообще существуют — задача про кино не
    // попадала в существующую «Личное», потому что модель о ней не знала.
    @Test
    void build_includesUserGroupNames() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, List.of("Личное", "Работа", "Покупки"));

        assertThat(parts.systemPrompt()).contains("Личное");
        assertThat(parts.systemPrompt()).contains("Работа");
        assertThat(parts.systemPrompt()).contains("Покупки");
    }

    @Test
    void build_instructsToChooseOnlyFromExistingGroups() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, List.of("Личное"));

        assertThat(parts.systemPrompt())
                .contains("единственно допустимые значения параметра group у действий propose_actions");
        assertThat(parts.systemPrompt())
                .contains("новых названий групп не придумывай");
    }

    // Блок Б: единственная новая строка правила — вызывать no_action вместо
    // propose_actions на вопросах о данных, репликах без содержания и
    // неразборчивых формулировках.
    @Test
    void build_instructsToUseNoActionForNonCommands() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).contains("no_action");
        assertThat(parts.systemPrompt()).contains("вопрос о данных");
        assertThat(parts.systemPrompt()).contains("реплика без содержания");
    }

    @Test
    void build_handlesNoGroupsYet() {
        var parts = builder.build(emptyWindow(), "любой текст", ZONE, noGroups());

        assertThat(parts.systemPrompt()).contains("Групп пока нет ни одной");
    }

    private List<String> noGroups() {
        return List.of();
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
