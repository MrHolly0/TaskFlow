package ru.taskflow.app.web.dto;

import ru.taskflow.app.application.AccountTransferResult;
import ru.taskflow.user.api.dto.IdentityDto;

public record IdentityBindResponse(
        IdentityDto identity,
        AccountTransferResult mergedFrom
) {
}
