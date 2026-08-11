package ru.taskflow.assistant.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Написан вручную намеренно: генерация маппинга уже приводила к тихой потере
 * поля в TaskMapper, когда имя геттера не совпало с ожидаемым.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProposalMapper {

    private final ObjectMapper objectMapper;

    public Proposal toDto(ProposalJpaEntity entity) {
        return new Proposal(
                entity.getId(),
                entity.getShortCode(),
                entity.getUserId(),
                ProposalStatus.valueOf(entity.getStatus()),
                entity.getSourceText(),
                entity.getClarification(),
                toActions(entity),
                entity.getCreatedAt(),
                entity.getExpiresAt()
        );
    }

    private List<ProposedAction> toActions(ProposalJpaEntity entity) {
        return entity.getActions().stream()
                .sorted(Comparator.comparingInt(ProposalActionJpaEntity::getOrdinal))
                .map(this::toAction)
                .toList();
    }

    private ProposedAction toAction(ProposalActionJpaEntity a) {
        return new ProposedAction(
                a.getOrdinal(),
                AssistantActionType.valueOf(a.getType()),
                a.getTargetTaskId(),
                readPayload(a.getPayload()),
                a.getSummary(),
                a.isAccepted()
        );
    }

    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Не удалось разобрать payload действия, вернём пустой: {}", json);
            return Map.of();
        }
    }
}
