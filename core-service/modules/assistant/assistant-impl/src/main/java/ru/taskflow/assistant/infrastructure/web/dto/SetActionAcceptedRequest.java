package ru.taskflow.assistant.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;

public record SetActionAcceptedRequest(@NotNull Boolean accepted) {
}
