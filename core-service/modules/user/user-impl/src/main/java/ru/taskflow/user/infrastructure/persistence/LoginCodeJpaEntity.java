package ru.taskflow.user.infrastructure.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import ru.taskflow.user.api.IdentityProvider;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "login_codes")
@Getter
@Setter
public class LoginCodeJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IdentityProvider channel;

    @Column(nullable = false, length = 320)
    private String identifier;

    @Column(name = "code_hash", nullable = false, length = 128)
    private String codeHash;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
