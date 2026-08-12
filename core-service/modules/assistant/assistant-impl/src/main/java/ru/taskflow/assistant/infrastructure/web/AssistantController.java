package ru.taskflow.assistant.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.application.AssistantRateLimiter;
import ru.taskflow.assistant.infrastructure.web.dto.SetActionAcceptedRequest;
import ru.taskflow.shared.exception.NotFoundException;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.io.IOException;
import java.util.UUID;

/**
 * Точки входа ассистента. NotFoundException ловится здесь же, локальным
 * @ExceptionHandler, а не общим GlobalExceptionHandler из core-service:app —
 * этот модуль не зависит от app (composition root), а тесты контроллера
 * поднимают его отдельным MockMvc без Spring-контекста.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
@Tag(name = "Assistant", description = "Диалоговый ассистент: разбор обращений и применение предложений")
public class AssistantController {

    private final AssistantService assistantService;
    private final AssistantRateLimiter rateLimiter;

    @PostMapping(value = "/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Обратиться к ассистенту", description = "Текст или аудио -> предложение")
    public Proposal handleMessage(
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @AuthenticationPrincipal AuthenticatedUser user
    ) throws IOException {
        if (!rateLimiter.allow(user.userId())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "слишком много обращений, попробуйте через минуту");
        }
        // Файл в приоритете над текстом, если пришли оба — клиент не должен
        // присылать оба поля одновременно, но если пришлёт, голос не теряем молча.
        if (file != null && !file.isEmpty()) {
            return assistantService.handleVoice(user.userId(), file.getBytes(), AssistantChannel.WEB);
        }
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "нужен текст или аудио");
        }
        return assistantService.handleText(user.userId(), text, AssistantChannel.WEB);
    }

    @GetMapping("/proposals/{id}")
    @Operation(summary = "Получить предложение")
    public Proposal findById(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return assistantService.findById(user.userId(), id);
    }

    @PatchMapping("/proposals/{id}/actions/{ordinal}")
    @Operation(summary = "Принять или отклонить отдельное действие предложения")
    public Proposal setActionAccepted(
            @PathVariable UUID id,
            @PathVariable int ordinal,
            @RequestBody @Valid SetActionAcceptedRequest request,
            @AuthenticationPrincipal AuthenticatedUser user
    ) {
        return assistantService.setActionAccepted(user.userId(), id, ordinal, request.accepted());
    }

    @PostMapping("/proposals/{id}/apply")
    @Operation(summary = "Применить предложение")
    public ApplyResult apply(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        return assistantService.apply(user.userId(), id);
    }

    @PostMapping("/proposals/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отклонить предложение целиком")
    public void reject(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
        assistantService.reject(user.userId(), id);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<String> handleNotFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
