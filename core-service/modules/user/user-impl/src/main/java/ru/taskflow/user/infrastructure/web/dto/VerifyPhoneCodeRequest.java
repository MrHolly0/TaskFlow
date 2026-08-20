package ru.taskflow.user.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyPhoneCodeRequest(
        @NotBlank String phone,
        @NotBlank @Pattern(regexp = "\\d{4}") String code
) {
}
