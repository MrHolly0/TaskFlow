package ru.taskflow.user.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import ru.taskflow.user.api.IdentityProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_identities",
        uniqueConstraints = @UniqueConstraint(name = "uq_identity_provider_external_id",
                columnNames = {"provider", "external_id"}))
@Getter
@Setter
public class UserIdentityJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserJpaEntity user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdentityProvider provider;

    @Column(name = "external_id", nullable = false, length = 320)
    private String externalId;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getUserId() {
        return user.getId();
    }
}
