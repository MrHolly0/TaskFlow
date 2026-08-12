package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class DuplicateGuard {

    private static final double JACCARD_THRESHOLD = 0.8;
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record GuardResult(List<ProposedAction> actions, List<String> rejections) {}

    public GuardResult filter(List<ProposedAction> actions, TaskContextWindow window) {
        return filter(actions, window, List.of());
    }

    /**
     * alreadyProposed — действия, уже принятые в предыдущем проходе того же обращения.
     * История второго прохода несёт модели только эхо search_tasks (иначе провайдер
     * отвергнет вызовы tool без ответа) — модель не видит, что уже предложила
     * create_task, и может предложить его снова. Здесь этот случай отлавливается
     * тем же правилом сравнения, что и для окна.
     */
    public GuardResult filter(List<ProposedAction> actions, TaskContextWindow window,
                               List<ProposedAction> alreadyProposed) {
        List<String> alreadyProposedTitles = alreadyProposed.stream()
                .filter(a -> a.type() == AssistantActionType.CREATE)
                .map(this::titleOf)
                .toList();

        List<ProposedAction> kept = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        for (ProposedAction action : actions) {
            if (action.type() != AssistantActionType.CREATE) {
                kept.add(action);
                continue;
            }

            String title = titleOf(action);
            String windowMatch = findMatch(title, window);
            if (windowMatch != null) {
                rejections.add("создание отклонено: похожая задача уже есть в списке ("
                        + windowMatch + " — " + window.title(windowMatch) + ")");
                continue;
            }

            String proposedMatch = findMatchAmongTitles(title, alreadyProposedTitles);
            if (proposedMatch != null) {
                rejections.add("создание отклонено: уже предложено в этом обращении (" + proposedMatch + ")");
                continue;
            }

            kept.add(action);
        }

        return new GuardResult(renumber(kept), rejections);
    }

    private String titleOf(ProposedAction action) {
        Object raw = action.payload().get("title");
        return raw == null ? "" : raw.toString();
    }

    private String findMatch(String proposedTitle, TaskContextWindow window) {
        String normalizedProposed = normalize(proposedTitle);
        Set<String> proposedWords = wordsOf(normalizedProposed);

        for (Map.Entry<String, String> entry : window.titles().entrySet()) {
            if (matches(normalizedProposed, proposedWords, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String findMatchAmongTitles(String proposedTitle, List<String> titles) {
        String normalizedProposed = normalize(proposedTitle);
        Set<String> proposedWords = wordsOf(normalizedProposed);

        for (String existing : titles) {
            if (matches(normalizedProposed, proposedWords, existing)) {
                return existing;
            }
        }
        return null;
    }

    private boolean matches(String normalizedProposed, Set<String> proposedWords, String otherTitle) {
        String normalizedOther = normalize(otherTitle);
        if (normalizedProposed.equals(normalizedOther)) {
            return true;
        }
        return jaccard(proposedWords, wordsOf(normalizedOther)) >= JACCARD_THRESHOLD;
    }

    private String normalize(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        String stripped = NON_WORD.matcher(lower).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    private Set<String> wordsOf(String normalized) {
        if (normalized.isBlank()) {
            return Set.of();
        }
        // Set.of(array) падает на повторяющемся слове (естественно для устной речи) — здесь дубли не ошибка.
        return new HashSet<>(Arrays.asList(WHITESPACE.split(normalized)));
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private List<ProposedAction> renumber(List<ProposedAction> actions) {
        List<ProposedAction> renumbered = new ArrayList<>(actions.size());
        int ordinal = 1;
        for (ProposedAction action : actions) {
            renumbered.add(new ProposedAction(ordinal++, action.type(), action.targetTaskId(),
                    action.payload(), action.summary(), action.accepted()));
        }
        return renumbered;
    }
}
