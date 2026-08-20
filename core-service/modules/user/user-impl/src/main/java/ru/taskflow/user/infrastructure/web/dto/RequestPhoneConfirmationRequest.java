package ru.taskflow.user.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RequestPhoneConfirmationRequest(@NotBlank String phone) {
}
