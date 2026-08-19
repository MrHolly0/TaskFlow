package ru.taskflow.app.web.dto;

import jakarta.validation.constraints.NotBlank;

public record MergeRequest(@NotBlank String token) {
}
