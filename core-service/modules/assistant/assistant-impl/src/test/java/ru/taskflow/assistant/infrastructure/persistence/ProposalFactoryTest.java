package ru.taskflow.assistant.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.application.AgentOutcome;
import ru.taskflow.assistant.application.ShortCodeGenerator;
import ru.taskflow.assistant.application.TaskContextWindow;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShortCodeGenerator shortCodeGenerator = new ShortCodeGenerator();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-12T10:00:00+03:00");
    private final Clock clock = Clock.fixed(now.toInstant(), ZoneOffset.ofHours(3));
    private final UUID userId = UUID.randomUUID();

    private ProposalFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ProposalFactory(shortCodeGenerator, objectMapper, clock);
    }

    private TaskContextWindow window(Map<String, UUID> refs) {
        Map<String, String> titles = new HashMap<>();
        refs.keySet().forEach(ref -> titles.put(ref, "задача " + ref));
        return new TaskContextWindow("", refs, titles);
    }

    private AgentOutcome outcome(List<ProposedAction> actions, String clarification, Map<String, UUID> refs) {
        return new AgentOutcome(actions, List.of(), clarification, List.of(), null, window(refs), 1, false);
    }

    @Test
    void from_generatesShortCode() {
        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(), null, Map.of()));

        assertThat(entity.getShortCode()).hasSize(8);
    }

    @Test
    void from_setsTwentyFourHourExpiry() {
        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(), null, Map.of()));

        assertThat(entity.getCreatedAt()).isEqualTo(now);
        assertThat(entity.getExpiresAt()).isEqualTo(now.plusHours(24));
    }

    @Test
    void from_serializesPayload() throws Exception {
        Map<String, Object> payload = Map.of("title", "купить молоко", "priority", "HIGH");
        ProposedAction action = new ProposedAction(1, AssistantActionType.CREATE, null, payload,
                "создать «купить молоко»", true);

        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(action), null, Map.of()));

        String rawPayload = entity.getActions().get(0).getPayload();
        Map<String, Object> restored = objectMapper.readValue(rawPayload, new TypeReference<Map<String, Object>>() {});
        assertThat(restored).isEqualTo(payload);
    }

    @Test
    void from_serializesTaskRefs() throws Exception {
        Map<String, UUID> refs = Map.of("T1", UUID.randomUUID());

        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(), null, refs));

        Map<String, UUID> restored = objectMapper.readValue(entity.getTaskRefs(), new TypeReference<Map<String, UUID>>() {});
        assertThat(restored).isEqualTo(refs);
    }

    @Test
    void from_preservesActionOrder() {
        ProposedAction first = new ProposedAction(5, AssistantActionType.CREATE, null, Map.of("title", "a"), "a", true);
        ProposedAction second = new ProposedAction(3, AssistantActionType.CREATE, null, Map.of("title", "b"), "b", true);

        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(first, second), null, Map.of()));

        assertThat(entity.getActions()).extracting(ProposalActionJpaEntity::getOrdinal).containsExactly(5, 3);
    }

    @Test
    void from_storesClarification() {
        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(), "Когда дедлайн?", Map.of()));

        assertThat(entity.getClarification()).isEqualTo("Когда дедлайн?");
        assertThat(entity.getActions()).isEmpty();
    }

    @Test
    void from_storesAmbiguityFlagAndReason() {
        AgentOutcome ambiguous = new AgentOutcome(List.of(), List.of(), null, List.of(), null,
                window(Map.of()), 1, false, true, "не понял, про какое кино речь", 0, 0);

        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT", ambiguous);

        assertThat(entity.isExclusive()).isTrue();
        assertThat(entity.getAmbiguityReason()).isEqualTo("не понял, про какое кино речь");
    }

    @Test
    void from_defaultsExclusiveToFalse() {
        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(), null, Map.of()));

        assertThat(entity.isExclusive()).isFalse();
        assertThat(entity.getAmbiguityReason()).isNull();
    }

    @Test
    void from_serializesRejections() throws Exception {
        List<String> rejections = List.of(
                "смена названия отклонена: текущее название задачи не упомянуто в реплике (Сдать отчёт)");
        AgentOutcome withRejections = new AgentOutcome(List.of(), rejections, null, List.of(), null,
                window(Map.of()), 1, false);

        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT", withRejections);

        List<String> restored = objectMapper.readValue(entity.getRejections(), new TypeReference<List<String>>() {});
        assertThat(restored).isEqualTo(rejections);
    }

    // Живой дефект: колонки input_tokens/output_tokens существуют, значения
    // приходят от nlp-worker в LlmToolResponse, но между ними не было связи —
    // AgentOutcome не нёс токены, ProposalFactory их не выставлял, в базу
    // всегда писался 0. Без этого измерение стоимости обращений невозможно.
    @Test
    void from_storesTokenUsage() {
        AgentOutcome withTokens = new AgentOutcome(List.of(), List.of(), null, List.of(), null,
                window(Map.of()), 1, false, false, null, 1234, 567);

        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT", withTokens);

        assertThat(entity.getInputTokens()).isEqualTo(1234);
        assertThat(entity.getOutputTokens()).isEqualTo(567);
    }

    @Test
    void from_defaultsTokenUsageToZero() {
        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(), null, Map.of()));

        assertThat(entity.getInputTokens()).isZero();
        assertThat(entity.getOutputTokens()).isZero();
    }

    @Test
    void from_serializesEmptyRejectionsList() throws Exception {
        ProposalJpaEntity entity = factory.from(userId, "текст", AssistantChannel.TELEGRAM, "TEXT",
                outcome(List.of(), null, Map.of()));

        List<String> restored = objectMapper.readValue(entity.getRejections(), new TypeReference<List<String>>() {});
        assertThat(restored).isEmpty();
    }
}
