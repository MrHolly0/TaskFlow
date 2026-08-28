package ru.taskflow.app.experiment;

import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Сверяет ProposedAction, реально предложенные моделью, с ExpectedAction из
 * датасета. Единственное место, где определяется, что считается ложным
 * срабатыванием (extra) — действие модели, не сопоставленное ни одному
 * ожидаемому того же типа (и той же цели, если она задана в датасете).
 * Без этого определения точность/полноту по ТЗ считать нельзя вовсе — их
 * здесь и даёт этот класс, разбором и явным правилом, а не догадкой.
 * <p>
 * Правило сопоставления пары (жадное, по порядку expected): совпадает тип
 * действия; если у ожидания задан targetRef — совпадает и разрешённый
 * targetTaskId; если задан titleContains — заголовок/сводка действия
 * содержат эту подстроку без учёта регистра. Из нескольких кандидатов
 * выбирается первый неиспользованный — датасет не обязан быть однозначным
 * до последней буквы, но повторные типы без targetRef/titleContains
 * сопоставляются в порядке появления, а не по смыслу.
 */
public final class ActionMatcher {

    public MatchResult match(List<ProposedAction> actual, ExpectedOutcome expected,
                              Map<String, UUID> setupRefToTaskId, LocalDate today, ZoneId zone) {
        return match(actual, expected, setupRefToTaskId, Map.of(), today, zone);
    }

    /**
     * setupRefToDeadline — реальный (не продекларированный в датасете) дедлайн
     * задач, заведённых перед обращением: SetupTaskSpec.deadlineOffsetDays даёт
     * только день, а не момент (задача создаётся в TaskService на "сейчас +
     * N дней", секунды и минуты не фиксированы) — отступ от срока (Б3/В)
     * можно сверить только по факту, а не по продекларированному offsetDays/Hour.
     */
    public MatchResult match(List<ProposedAction> actual, ExpectedOutcome expected,
                              Map<String, UUID> setupRefToTaskId, Map<String, OffsetDateTime> setupRefToDeadline,
                              LocalDate today, ZoneId zone) {
        List<ExpectedAction> expectedActions = expected.actions();
        boolean[] claimed = new boolean[actual.size()];
        List<MatchedPair> matched = new ArrayList<>();
        List<String> missingDescriptions = new ArrayList<>();
        List<ExpectedAction> missingExpected = new ArrayList<>();

        for (ExpectedAction expectedAction : expectedActions) {
            int foundIndex = -1;
            for (int i = 0; i < actual.size(); i++) {
                if (claimed[i]) {
                    continue;
                }
                if (matches(actual.get(i), expectedAction, setupRefToTaskId)) {
                    foundIndex = i;
                    break;
                }
            }
            if (foundIndex == -1) {
                missingDescriptions.add(describeExpected(expectedAction));
                missingExpected.add(expectedAction);
                continue;
            }
            claimed[foundIndex] = true;
            matched.add(new MatchedPair(actual.get(foundIndex), expectedAction,
                    attributeMismatches(actual.get(foundIndex), expectedAction, setupRefToDeadline, today, zone)));
        }

        List<String> extra = new ArrayList<>();
        for (int i = 0; i < actual.size(); i++) {
            if (!claimed[i]) {
                extra.add(describeActual(actual.get(i)));
            }
        }

        return new MatchResult(expectedActions.size(), actual.size(), matched.size(),
                missingDescriptions.size(), extra.size(), matched, missingDescriptions, missingExpected, extra);
    }

    private boolean matches(ProposedAction action, ExpectedAction expected, Map<String, UUID> setupRefToTaskId) {
        if (!typeMatches(action.type(), expected.type())) {
            return false;
        }
        if (expected.targetRef() != null) {
            UUID expectedTargetId = setupRefToTaskId.get(expected.targetRef());
            if (expectedTargetId == null || !expectedTargetId.equals(action.targetTaskId())) {
                return false;
            }
        }
        if (expected.titleContains() != null && !expected.titleContains().isBlank()) {
            String haystack = (titleOf(action) + " " + action.summary()).toLowerCase(Locale.ROOT);
            if (!haystack.contains(expected.titleContains().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private boolean typeMatches(AssistantActionType actual, String expectedType) {
        if (expectedType == null) {
            return false;
        }
        try {
            return actual == AssistantActionType.valueOf(expectedType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<String> attributeMismatches(ProposedAction action, ExpectedAction expected,
                                              Map<String, OffsetDateTime> setupRefToDeadline,
                                              LocalDate today, ZoneId zone) {
        List<String> mismatches = new ArrayList<>();

        if (expected.deadlineOffsetDays() != null) {
            LocalDate expectedDate = today.plusDays(expected.deadlineOffsetDays());
            LocalDate actualDate = dateOf(action, zone);
            if (!expectedDate.equals(actualDate)) {
                mismatches.add("дата: ожидалось %s, получено %s".formatted(expectedDate, actualDate));
            }
        }
        if (expected.deadlineHour() != null) {
            Integer actualHour = hourOf(action);
            if (!expected.deadlineHour().equals(actualHour)) {
                mismatches.add("час: ожидалось %d, получено %s".formatted(expected.deadlineHour(), actualHour));
            }
        }
        if (expected.priority() != null) {
            Object actualPriority = action.payload().get("priority");
            if (!expected.priority().equalsIgnoreCase(String.valueOf(actualPriority))) {
                mismatches.add("приоритет: ожидалось %s, получено %s".formatted(expected.priority(), actualPriority));
            }
        }
        if (expected.group() != null) {
            Object actualGroup = action.payload().get("group");
            if (!expected.group().equalsIgnoreCase(String.valueOf(actualGroup))) {
                mismatches.add("группа: ожидалось %s, получено %s".formatted(expected.group(), actualGroup));
            }
        }
        if (expected.reminderOffsetDays() != null) {
            LocalDate expectedDate = today.plusDays(expected.reminderOffsetDays());
            OffsetDateTime reminderAt = reminderAtOf(action);
            LocalDate actualDate = reminderAt == null ? null : reminderAt.atZoneSameInstant(zone).toLocalDate();
            if (!expectedDate.equals(actualDate)) {
                mismatches.add("дата напоминания: ожидалось %s, получено %s".formatted(expectedDate, actualDate));
            }
        }
        if (expected.reminderHour() != null) {
            OffsetDateTime reminderAt = reminderAtOf(action);
            Integer actualHour = reminderAt == null ? null : reminderAt.atZoneSameInstant(zone).getHour();
            if (!expected.reminderHour().equals(actualHour)) {
                mismatches.add("час напоминания: ожидалось %d, получено %s".formatted(expected.reminderHour(), actualHour));
            }
        }
        if (expected.reminderMinutesBeforeTargetDeadline() != null) {
            mismatches.addAll(reminderOffsetMismatch(action, expected, setupRefToDeadline));
        }
        if (expected.reminderMinutesBeforeTargetDeadlineMin() != null || expected.reminderMinutesBeforeTargetDeadlineMax() != null) {
            mismatches.addAll(reminderOffsetRangeMismatch(action, expected, setupRefToDeadline));
        }
        if (expected.reminderHourMin() != null || expected.reminderHourMax() != null) {
            mismatches.addAll(reminderHourRangeMismatch(action, expected, zone));
        }
        if (Boolean.TRUE.equals(expected.expectNoReminder())) {
            mismatches.addAll(noReminderMismatch(action));
        }
        if (expected.plannedDateOffsetDaysMin() != null || expected.plannedDateOffsetDaysMax() != null) {
            mismatches.addAll(plannedDateOffsetRangeMismatch(action, expected, today, zone));
        }
        if (Boolean.TRUE.equals(expected.expectNoPlannedDate())) {
            mismatches.addAll(noPlannedDateMismatch(action));
        }
        return mismatches;
    }

    // День исполнения (блок В) — тот же приём диапазона, что и у отступа
    // напоминания, но от дня прогона: у planned_date нет цели-дедлайна,
    // это атрибут самой создаваемой задачи, а не отступ от чего-то ещё.
    private List<String> plannedDateOffsetRangeMismatch(ProposedAction action, ExpectedAction expected,
                                                          LocalDate today, ZoneId zone) {
        OffsetDateTime plannedDate = plannedDateAtOf(action);
        if (plannedDate == null) {
            return List.of("день исполнения: не удалось сверить (нет planned_date)");
        }
        long actualOffsetDays = ChronoUnit.DAYS.between(today, plannedDate.atZoneSameInstant(zone).toLocalDate());
        Integer min = expected.plannedDateOffsetDaysMin();
        Integer max = expected.plannedDateOffsetDaysMax();
        if ((min != null && actualOffsetDays < min) || (max != null && actualOffsetDays > max)) {
            return List.of("день исполнения: ожидалось смещение %s–%s дн. от сегодня, получено %d"
                    .formatted(min == null ? "…" : min, max == null ? "…" : max, actualOffsetDays));
        }
        return List.of();
    }

    // "Решила не предлагать" отличимо от "забыла предложить" для дня
    // исполнения — та же логика, что и noReminderMismatch.
    private List<String> noPlannedDateMismatch(ProposedAction action) {
        List<String> mismatches = new ArrayList<>();
        if (plannedDateAtOf(action) != null) {
            mismatches.add("день исполнения: ожидалось отсутствие, получено " + plannedDateAtOf(action));
        }
        if (!Boolean.TRUE.equals(action.payload().get("no_planned_date_needed"))) {
            mismatches.add("день исполнения: planned_date пуст, но no_planned_date_needed не выставлен — "
                    + "неотличимо от того, что модель забыла решить");
        }
        return mismatches;
    }

    private OffsetDateTime plannedDateAtOf(ProposedAction action) {
        Object raw = action.payload().get("planned_date");
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    // Сверка по факту (реальный дедлайн заведённой задачи), не по offsetDays/Hour
    // из датасета — см. javadoc у перегрузки match() с setupRefToDeadline.
    // Минутная точность (truncatedTo MINUTES) намеренно грубее секунды: модель
    // видит дедлайн в окне контекста отрендеренным до минуты (dd.MM HH:mm),
    // сверка секундами наказывала бы за точность, которой у модели нет.
    private List<String> reminderOffsetMismatch(ProposedAction action, ExpectedAction expected,
                                                 Map<String, OffsetDateTime> setupRefToDeadline) {
        OffsetDateTime targetDeadline = expected.targetRef() == null ? null : setupRefToDeadline.get(expected.targetRef());
        OffsetDateTime reminderAt = reminderAtOf(action);
        if (targetDeadline == null || reminderAt == null) {
            return List.of("отступ от срока: не удалось сверить (нет дедлайна цели или reminder_at)");
        }
        OffsetDateTime expectedReminderAt = targetDeadline.minusMinutes(expected.reminderMinutesBeforeTargetDeadline());
        if (!expectedReminderAt.truncatedTo(ChronoUnit.MINUTES).isEqual(reminderAt.truncatedTo(ChronoUnit.MINUTES))) {
            return List.of("отступ от срока: ожидалось %s (%d мин. до дедлайна цели), получено %s"
                    .formatted(expectedReminderAt, expected.reminderMinutesBeforeTargetDeadline(), reminderAt));
        }
        return List.of();
    }

    // Диапазон вместо точного числа (блок Г) — модель сама подбирает отступ,
    // «за 15–40 минут» проверяемо, «ровно в 17:30» нет. Открытый конец
    // диапазона (только Min или только Max) не проверяется — это осознанный
    // выбор датасета, не пропуск.
    private List<String> reminderOffsetRangeMismatch(ProposedAction action, ExpectedAction expected,
                                                       Map<String, OffsetDateTime> setupRefToDeadline) {
        OffsetDateTime targetDeadline = expected.targetRef() == null ? null : setupRefToDeadline.get(expected.targetRef());
        OffsetDateTime reminderAt = reminderAtOf(action);
        if (targetDeadline == null || reminderAt == null) {
            return List.of("отступ от срока: не удалось сверить (нет дедлайна цели или reminder_at)");
        }
        long actualMinutesBefore = ChronoUnit.MINUTES.between(reminderAt, targetDeadline);
        Integer min = expected.reminderMinutesBeforeTargetDeadlineMin();
        Integer max = expected.reminderMinutesBeforeTargetDeadlineMax();
        if ((min != null && actualMinutesBefore < min) || (max != null && actualMinutesBefore > max)) {
            return List.of("отступ от срока: ожидалось %s–%s мин. до дедлайна цели, получено %d"
                    .formatted(min == null ? "…" : min, max == null ? "…" : max, actualMinutesBefore));
        }
        return List.of();
    }

    // Для напоминаний без срока-цели ("рабочие часы") — диапазон часа
    // локального времени, та же логика открытых границ.
    private List<String> reminderHourRangeMismatch(ProposedAction action, ExpectedAction expected, ZoneId zone) {
        OffsetDateTime reminderAt = reminderAtOf(action);
        if (reminderAt == null) {
            return List.of("час напоминания: не удалось сверить (нет reminder_at)");
        }
        int actualHour = reminderAt.atZoneSameInstant(zone).getHour();
        Integer min = expected.reminderHourMin();
        Integer max = expected.reminderHourMax();
        if ((min != null && actualHour < min) || (max != null && actualHour > max)) {
            return List.of("час напоминания: ожидалось %s–%s, получено %d"
                    .formatted(min == null ? "…" : min, max == null ? "…" : max, actualHour));
        }
        return List.of();
    }

    // "Решила не предлагать" отличимо от "забыла предложить" (блок Г) —
    // reminder_at пуст и no_reminder_needed=true у самого действия, а не
    // просто отсутствие reminder_at само по себе (то неотличимо от забыла).
    private List<String> noReminderMismatch(ProposedAction action) {
        List<String> mismatches = new ArrayList<>();
        if (reminderAtOf(action) != null) {
            mismatches.add("напоминание: ожидалось отсутствие, получено " + reminderAtOf(action));
        }
        if (!Boolean.TRUE.equals(action.payload().get("no_reminder_needed"))) {
            mismatches.add("напоминание: reminder_at пуст, но no_reminder_needed не выставлен — "
                    + "неотличимо от того, что модель забыла решить");
        }
        return mismatches;
    }

    private OffsetDateTime reminderAtOf(ProposedAction action) {
        Object raw = action.payload().get("reminder_at");
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String titleOf(ProposedAction action) {
        Object title = action.payload().get("title");
        return title == null ? "" : title.toString();
    }

    private OffsetDateTime deadlineOf(ProposedAction action) {
        Object raw = action.type() == AssistantActionType.RESCHEDULE
                ? action.payload().get("new_deadline")
                : action.payload().get("deadline");
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate dateOf(ProposedAction action, ZoneId zone) {
        OffsetDateTime deadline = deadlineOf(action);
        return deadline == null ? null : deadline.atZoneSameInstant(zone).toLocalDate();
    }

    private Integer hourOf(ProposedAction action) {
        OffsetDateTime deadline = deadlineOf(action);
        return deadline == null ? null : deadline.getHour();
    }

    private String describeExpected(ExpectedAction expected) {
        return "ожидалось %s%s%s".formatted(expected.type(),
                expected.titleContains() != null ? " (\"" + expected.titleContains() + "\")" : "",
                expected.targetRef() != null ? " → " + expected.targetRef() : "");
    }

    private String describeActual(ProposedAction action) {
        return "лишнее: " + action.summary();
    }

    public record MatchedPair(ProposedAction actual, ExpectedAction expected, List<String> attributeMismatches) {}

    public record MatchResult(
            int expectedCount,
            int actualCount,
            int matchedCount,
            int missingCount,
            int extraCount,
            List<MatchedPair> matched,
            List<String> missingDescriptions,
            List<ExpectedAction> missingExpected,
            List<String> extraDescriptions
    ) {
        public boolean countCorrect() {
            return expectedCount == actualCount;
        }

        public boolean fullyCorrect() {
            return missingCount == 0 && extraCount == 0
                    && matched.stream().allMatch(p -> p.attributeMismatches().isEmpty());
        }

        /**
         * "TYPE:matched/expected;..." по каждому типу операции, встретившемуся
         * среди ожиданий этой строки — источник таблицы Б3 "операция — верно —
         * ошибок — доля верных" без отдельного файла на пару действие/ожидание.
         */
        public String perTypeBreakdown() {
            var expectedByType = new java.util.TreeMap<String, Integer>();
            for (MatchedPair pair : matched) {
                expectedByType.merge(pair.expected().type(), 1, Integer::sum);
            }
            var matchedByType = new java.util.TreeMap<String, Integer>();
            for (MatchedPair pair : matched) {
                matchedByType.merge(pair.expected().type(), 1, Integer::sum);
            }
            for (ExpectedAction missed : missingExpected) {
                expectedByType.merge(missed.type(), 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            for (var entry : expectedByType.entrySet()) {
                if (!sb.isEmpty()) {
                    sb.append(';');
                }
                int matchedForType = matchedByType.getOrDefault(entry.getKey(), 0);
                sb.append(entry.getKey()).append(':').append(matchedForType).append('/').append(entry.getValue());
            }
            return sb.toString();
        }
    }
}
