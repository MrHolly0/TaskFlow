package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.ArrayList;
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
        List<ProposedAction> kept = new ArrayList<>();
        List<String> rejections = new ArrayList<>();

        for (ProposedAction action : actions) {
            if (action.type() != AssistantActionType.CREATE) {
                kept.add(action);
                continue;
            }

            String title = titleOf(action);
            String match = findMatch(title, window);
            if (match != null) {
                rejections.add("создание отклонено: похожая задача уже есть в списке ("
                        + match + " — " + window.title(match) + ")");
            } else {
                kept.add(action);
            }
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
            String normalizedExisting = normalize(entry.getValue());
            if (normalizedProposed.equals(normalizedExisting)) {
                return entry.getKey();
            }
            if (jaccard(proposedWords, wordsOf(normalizedExisting)) >= JACCARD_THRESHOLD) {
                return entry.getKey();
            }
        }
        return null;
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
        return Set.of(WHITESPACE.split(normalized));
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
