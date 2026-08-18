package ru.taskflow.user.api.dto;

import ru.taskflow.user.api.IdentityProvider;

import java.time.OffsetDateTime;

public record IdentityDto(
        IdentityProvider provider,
        String externalId,
        OffsetDateTime verifiedAt
) {
}
