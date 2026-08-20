package ru.taskflow.user.infrastructure.web.dto;

public record AuthMethodsResponse(boolean email, boolean telegram, boolean phone) {
}
