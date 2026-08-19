package ru.taskflow.assistant.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "assistant_proposals")
@Getter
@Setter
public class ProposalJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "short_code", nullable = false, length = 12, unique = true)
    private String shortCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_channel", nullable = false, length = 16)
    private String sourceChannel;

    @Column(name = "input_kind", nullable = false, length = 8)
    private String inputKind;

    @Column(name = "source_text", columnDefinition = "TEXT")
    private String sourceText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_refs", columnDefinition = "jsonb")
    private String taskRefs;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String clarification;

    @Column(nullable = false)
    private boolean exclusive;

    @Column(name = "ambiguity_reason", columnDefinition = "TEXT")
    private String ambiguityReason;

    @Column(name = "llm_passes")
    private int llmPasses;

    @Column(name = "input_tokens")
    private int inputTokens;

    @Column(name = "output_tokens")
    private int outputTokens;

    @Column(name = "latency_ms")
    private int latencyMs;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordinal ASC")
    private List<ProposalActionJpaEntity> actions = new ArrayList<>();

    public void addAction(ProposalActionJpaEntity action) {
        actions.add(action);
        action.setProposal(this);
    }
}
