package ru.taskflow.assistant.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.FocusPlannedDateGenerator;
import ru.taskflow.assistant.api.PlannedDateGeneration;
import ru.taskflow.assistant.infrastructure.persistence.ProposalRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FocusPlannedDateService implements FocusPlannedDateGenerator {

    private final PlannedDateSuggester plannedDateSuggester;
    private final ProposalRepository proposalRepository;

    @Override
    public PlannedDateGeneration generate(UUID taskId, String title, String description, LocalDate today, ZoneId zone) {
        String originalMessage = proposalRepository
                .findSourceTextsByAppliedTaskId(taskId, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(title);
        PlannedDateSuggestion suggestion = plannedDateSuggester.suggest(
                title, description, originalMessage, today, zone);
        return new PlannedDateGeneration(
                suggestion.plannedDate(),
                suggestion.noPlannedDateNeeded(),
                suggestion.inputTokens(),
                suggestion.outputTokens());
    }
}
