package ru.taskflow.user.infrastructure.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RequestCodeRequest(@NotBlank @Email String email) {
}
