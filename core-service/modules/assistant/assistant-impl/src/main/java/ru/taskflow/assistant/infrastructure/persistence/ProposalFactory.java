package ru.taskflow.assistant.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.assistant.application.AgentOutcome;
import ru.taskflow.assistant.application.ShortCodeGenerator;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * taskRefs сохраняем не ради применения (в действиях уже разрешённый targetTaskId),
 * а ради ответа на уточняющий вопрос: следующий проход после ask_user обязан
 * работать с тем же окном ярлыков, иначе они разъедутся.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProposalFactory {

    private final ShortCodeGenerator shortCodeGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProposalJpaEntity from(UUID userId, String sourceText, AssistantChannel channel,
                                   String inputKind, AgentOutcome outcome) {
        OffsetDateTime now = OffsetDateTime.now(clock);

        ProposalJpaEntity entity = new ProposalJpaEntity();
        entity.setShortCode(shortCodeGenerator.generate());
        entity.setUserId(userId);
        entity.setSourceChannel(channel.name());
        entity.setInputKind(inputKind);
        entity.setSourceText(sourceText);
        entity.setTaskRefs(writeJson(outcome.window().refs()));
        entity.setStatus(ProposalStatus.PENDING.name());
        entity.setClarification(outcome.clarification());
        entity.setExclusive(outcome.ambiguous());
        entity.setAmbiguityReason(outcome.ambiguityReason());
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plusHours(24));

        for (ProposedAction action : outcome.actions()) {
            entity.addAction(toActionEntity(action));
        }

        return entity;
    }

    private ProposalActionJpaEntity toActionEntity(ProposedAction action) {
        ProposalActionJpaEntity entity = new ProposalActionJpaEntity();
        entity.setOrdinal(action.ordinal());
        entity.setType(action.type().name());
        entity.setTargetTaskId(action.targetTaskId());
        entity.setPayload(writeJson(action.payload()));
        entity.setSummary(action.summary());
        entity.setAccepted(action.accepted());
        return entity;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Не удалось сериализовать данные предложения, сохраним пустой объект", e);
            return "{}";
        }
    }
}
