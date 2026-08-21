package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ActionOutcome;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.telegram.application.ProposalMessageRenderer;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender.InlineButton;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalMessageRendererTest {

    private final ProposalMessageRenderer renderer = new ProposalMessageRenderer();
    private final UUID userId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.now();

    @Test
    void render_showsActionsWithCheckmarks() {
        var accepted = new ProposedAction(1, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть «купить молоко»", true);
        var rejected = new ProposedAction(2, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть «сделать зарядку»", false);
        Proposal proposal = new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "текст", null, List.of(accepted, rejected), now, now.plusHours(24));

        String rendered = renderer.render(proposal);

        assertThat(rendered).contains("☑").contains("закрыть «купить молоко»");
        assertThat(rendered).contains("☐").contains("закрыть «сделать зарядку»");
    }

    @Test
    void render_showsRejectionsBelowActionsInCalmTone() {
        var action = new ProposedAction(1, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть «купить молоко»", true);
        Proposal proposal = new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "текст", null, List.of(action), now, now.plusHours(24), false, null,
                List.of("смена названия отклонена: текущее название задачи не упомянуто в реплике (Сдать отчёт)"));

        String rendered = renderer.render(proposal);

        assertThat(rendered).contains("закрыть «купить молоко»");
        assertThat(rendered).contains("Не учтено:");
        assertThat(rendered).contains("текущее название задачи не упомянуто в реплике (Сдать отчёт)");
    }

    @Test
    void render_showsRejectionsWhenNoActionsProposed() {
        Proposal proposal = new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "текст", null, List.of(), now, now.plusHours(24), false, null,
                List.of("создание отклонено: похожая задача уже есть в списке (T1 — купить молоко)"));

        String rendered = renderer.render(proposal);

        assertThat(rendered).contains("Не нашёл, что предложить");
        assertThat(rendered).contains("похожая задача уже есть в списке");
    }

    @Test
    void render_omitsRejectionsBlockWhenThereAreNone() {
        var action = new ProposedAction(1, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть «купить молоко»", true);
        Proposal proposal = new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "текст", null, List.of(action), now, now.plusHours(24));

        String rendered = renderer.render(proposal);

        assertThat(rendered).doesNotContain("Не учтено:");
    }

    @Test
    void render_showsClarificationText() {
        Proposal proposal = new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "текст", "Какую встречу перенести?", List.of(), now, now.plusHours(24));

        String rendered = renderer.render(proposal);

        assertThat(rendered).isEqualTo("Какую встречу перенести?");
    }

    @Test
    void keyboard_includesToggleApplyRejectButtons() {
        var action = new ProposedAction(1, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть задачу", true);
        Proposal proposal = new Proposal(UUID.randomUUID(), "SHORT123", userId, ProposalStatus.PENDING,
                "текст", null, List.of(action), now, now.plusHours(24));

        List<List<InlineButton>> keyboard = renderer.keyboard(proposal);

        assertThat(keyboard).hasSize(2);
        assertThat(keyboard.get(0)).extracting(InlineButton::callbackData).containsExactly("p:SHORT123:t:1");
        assertThat(keyboard.get(1)).extracting(InlineButton::callbackData)
                .containsExactly("p:SHORT123:a", "p:SHORT123:r");
    }

    @Test
    void keyboard_callbackDataStaysWithinTelegramLimit() {
        var action = new ProposedAction(999, AssistantActionType.COMPLETE, UUID.randomUUID(), Map.of(), "закрыть задачу", true);
        Proposal proposal = new Proposal(UUID.randomUUID(), "ABCDEFGH", userId, ProposalStatus.PENDING,
                "текст", null, List.of(action), now, now.plusHours(24));

        List<List<InlineButton>> keyboard = renderer.keyboard(proposal);

        for (List<InlineButton> row : keyboard) {
            for (InlineButton button : row) {
                int byteLength = button.callbackData().getBytes(StandardCharsets.UTF_8).length;
                assertThat(byteLength).isLessThanOrEqualTo(64);
            }
        }
    }

    @Test
    void renderApplyResult_showsHonestSummaryWithFailures() {
        var result = new ApplyResult(ProposalStatus.PARTIALLY_APPLIED, 2, 3, List.of(
                new ActionOutcome(1, "закрыть «купить молоко»", true, null),
                new ActionOutcome(2, "закрыть «позвонить Марку»", true, null),
                new ActionOutcome(3, "закрыть «обсудить с Марком договор»", false, "уже была закрыта раньше")
        ));

        String rendered = renderer.renderApplyResult(result);

        assertThat(rendered).contains("2 из 3");
        assertThat(rendered).contains("обсудить с Марком договор").contains("уже была закрыта раньше");
    }
}
