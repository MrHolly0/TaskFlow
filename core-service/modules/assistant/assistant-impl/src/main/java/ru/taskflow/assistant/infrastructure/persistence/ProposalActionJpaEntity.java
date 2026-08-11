package ru.taskflow.assistant.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "assistant_proposal_actions")
@Getter
@Setter
public class ProposalActionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposal_id", nullable = false)
    private ProposalJpaEntity proposal;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "target_task_id")
    private UUID targetTaskId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false, length = 256)
    private String summary;

    @Column(nullable = false)
    private boolean accepted = true;

    @Column(name = "applied_task_id")
    private UUID appliedTaskId;

    @Column(name = "apply_error", length = 256)
    private String applyError;
}
