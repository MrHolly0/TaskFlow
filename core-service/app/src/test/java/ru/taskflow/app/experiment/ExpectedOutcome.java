package ru.taskflow.app.experiment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ExpectedOutcome(
        @JsonProperty("action_count") int actionCount,
        boolean ambiguous,
        List<ExpectedAction> actions
) {
    public ExpectedOutcome {
        if (actions == null) {
            actions = List.of();
        }
    }
}
