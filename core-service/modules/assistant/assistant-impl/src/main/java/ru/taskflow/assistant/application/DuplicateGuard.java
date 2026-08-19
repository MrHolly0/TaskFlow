package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DuplicateGuard {

    private static final double JACCARD_THRESHOLD = 0.8;

    private final TitleSimilarity titleSimilarity;

    public record GuardResult(List<ProposedAction> actions, List<String> rejections) {}

    /**
     * Порог дубля — решение DuplicateGuard, не общая величина: AgentLoop
     * спрашивает через этот метод, а не сравнивает с порогом сам.
     */
    public boolean isDuplicateOf(String proposedText, String existingTitle) {
        return existingTitle != null && titleSimilarity.similarity(proposedText, existingTitle) >= JACCARD_THRESHOLD;
    }

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
        for (Map.Entry<String, String> entry : window.titles().entrySet()) {
            if (isDuplicateOf(proposedTitle, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String findMatchAmongTitles(String proposedTitle, List<String> titles) {
        for (String existing : titles) {
            if (isDuplicateOf(proposedTitle, existing)) {
                return existing;
            }
        }
        return null;
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
