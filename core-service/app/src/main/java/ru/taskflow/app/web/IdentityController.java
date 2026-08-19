package ru.taskflow.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.app.application.AccountTransferService;
import ru.taskflow.app.web.dto.IdentityBindResponse;
import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.shared.security.TelegramLoginWidgetValidator;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.api.dto.IdentityDto;
import ru.taskflow.user.application.EmailSender;
import ru.taskflow.user.application.LoginCodeService;
import ru.taskflow.user.infrastructure.web.dto.RequestCodeRequest;
import ru.taskflow.user.infrastructure.web.dto.TelegramLoginWidgetAuthRequest;
import ru.taskflow.user.infrastructure.web.dto.VerifyCodeRequest;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Привязка способов входа изнутри уже авторизованной учётки — не то же
 * самое, что вход: учётка известна из токена, здесь только проверяем, не
 * занят ли идентификатор кем-то другим. Живёт в app (не в user-impl), потому
 * что при конфликте сразу переносит данные — а это задевает task/notify/
 * audit/assistant, которые user-impl не видит по правилам зависимостей
 * модулей. Перенос происходит в том же запросе, где доказано владение
 * идентификатором (код с почты или подпись Telegram Login Widget) — без
 * отдельного «подтверди перенос» шага, который потребовал бы доказывать
 * владение второй раз.
 */
@RestController
@RequestMapping("/api/v1/identities")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Identities", description = "Управление способами входа привязанными к учётке")
public class IdentityController {

    private final UserService userService;
    private final LoginCodeService loginCodeService;
    private final EmailSender emailSender;
    private final TelegramLoginWidgetValidator loginWidgetValidator;
    private final AccountTransferService accountTransferService;

    @GetMapping
    @Operation(summary = "Список способов входа", description = "Все идентичности, привязанные к текущей учётке")
    public List<IdentityDto> listIdentities(@AuthenticationPrincipal AuthenticatedUser user) {
        return userService.listIdentities(user.userId());
    }

    @PostMapping("/email/request-code")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Запросить код для привязки почты",
            description = "Отвечает одинаково независимо от того, дошло ли письмо")
    public void requestEmailCode(@Valid @RequestBody RequestCodeRequest request) {
        String code = loginCodeService.issueCode(request.email());
        try {
            emailSender.sendLoginCode(request.email(), code);
            loginCodeService.confirmIssued(request.email(), code);
        } catch (RuntimeException e) {
            log.warn("Не удалось отправить код привязки: {}", e.getMessage());
        }
    }

    @PostMapping("/email/confirm")
    @Operation(summary = "Подтвердить почту кодом",
            description = "Привязывает почту к текущей учётке; если почта уже принадлежит другой — переносит её данные сюда")
    public IdentityBindResponse confirmEmail(@Valid @RequestBody VerifyCodeRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        if (!loginCodeService.verifyCode(request.email(), request.code())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid code");
        }
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);
        return bindWithTransfer(user.userId(), IdentityProvider.EMAIL, normalizedEmail);
    }

    @PostMapping("/telegram")
    @Operation(summary = "Привязать Telegram",
            description = "Проверяет данные Login Widget; если Telegram уже привязан к другой учётке — переносит её данные сюда")
    public IdentityBindResponse bindTelegram(@Valid @RequestBody TelegramLoginWidgetAuthRequest request,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        if (!loginWidgetValidator.validate(request.fields())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login widget data");
        }
        Map<String, String> f = request.fields();
        return bindWithTransfer(user.userId(), IdentityProvider.TELEGRAM, f.get("id"));
    }

    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отвязать способ входа", description = "Нельзя отвязать последний способ")
    public void unbind(@PathVariable IdentityProvider provider, @AuthenticationPrincipal AuthenticatedUser user) {
        userService.unbindIdentity(user.userId(), provider);
    }

    private IdentityBindResponse bindWithTransfer(UUID userId, IdentityProvider provider, String externalId) {
        var owner = userService.findIdentityOwner(provider, externalId);
        if (owner.isPresent() && !owner.get().equals(userId)) {
            var transferred = accountTransferService.transfer(owner.get(), userId);
            var identity = userService.bindIdentity(userId, provider, externalId);
            return new IdentityBindResponse(identity, transferred);
        }
        var identity = userService.bindIdentity(userId, provider, externalId);
        return new IdentityBindResponse(identity, null);
    }
}
