package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuickAddPolicyTest {

    private final QuickAddPolicy policy = new QuickAddPolicy();

    @Test
    void shouldAutoApply_trueWhenOnlyCreateActions() {
        Proposal proposal = proposal(ProposalStatus.PENDING, null,
                action(1, AssistantActionType.CREATE), action(2, AssistantActionType.CREATE));

        assertThat(policy.shouldAutoApply(proposal)).isTrue();
    }

    @Test
    void shouldAutoApply_falseWhenActionTouchesExistingTask() {
        Proposal proposal = proposal(ProposalStatus.PENDING, null,
                action(1, AssistantActionType.CREATE), action(2, AssistantActionType.COMPLETE));

        assertThat(policy.shouldAutoApply(proposal)).isFalse();
    }

    @Test
    void shouldAutoApply_falseWhenClarification() {
        Proposal proposal = proposal(ProposalStatus.PENDING, "какую встречу перенести?");

        assertThat(policy.shouldAutoApply(proposal)).isFalse();
    }

    @Test
    void shouldAutoApply_falseWhenNoActions() {
        Proposal proposal = proposal(ProposalStatus.PENDING, null);

        assertThat(policy.shouldAutoApply(proposal)).isFalse();
    }

    @Test
    void shouldAutoApply_falseWhenDegraded() {
        // путь деградации Task 23: status=FAILED, id=null, вместо предложения — задача-заглушка
        Proposal proposal = new Proposal(null, null, UUID.randomUUID(), ProposalStatus.FAILED,
                "закрой молоко", "не удалось разобрать сообщение", List.of(),
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24));

        assertThat(policy.shouldAutoApply(proposal)).isFalse();
    }

    private Proposal proposal(ProposalStatus status, String clarification, ProposedAction... actions) {
        return new Proposal(UUID.randomUUID(), "CODE1234", UUID.randomUUID(), status,
                "закрой молоко и купи хлеб", clarification, List.of(actions),
                OffsetDateTime.now(), OffsetDateTime.now().plusHours(24));
    }

    private ProposedAction action(int ordinal, AssistantActionType type) {
        return new ProposedAction(ordinal, type, null, Map.of(), "действие " + ordinal, true);
    }
}
