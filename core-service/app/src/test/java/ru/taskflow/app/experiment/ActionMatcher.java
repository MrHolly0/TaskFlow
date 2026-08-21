package ru.taskflow.app.experiment;

import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
                    attributeMismatches(actual.get(foundIndex), expectedAction, today, zone)));
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
        return mismatches;
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
