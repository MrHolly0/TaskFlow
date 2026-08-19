package ru.taskflow.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.app.application.AccountTransferService;
import ru.taskflow.app.application.MergeTokenService;
import ru.taskflow.app.web.dto.IdentityBindResponse;
import ru.taskflow.app.web.dto.MergeConflictResponse;
import ru.taskflow.app.web.dto.MergeRequest;
import ru.taskflow.shared.security.AuthenticatedUser;
import ru.taskflow.shared.security.TelegramLoginWidgetValidator;
import ru.taskflow.task.api.TaskService;
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
 * что при конфликте задевает task/notify/audit/assistant, которые user-impl
 * не видит по правилам зависимостей модулей.
 *
 * Доказательство владения идентификатором (код с почты, подпись Telegram
 * Login Widget) и согласие на слияние двух учёток — разные вещи, первое не
 * влечёт второе. Поэтому при конфликте бэкенд не переносит данные сразу:
 * отвечает 409 с составом чужой учётки и одноразовым токеном согласия
 * (MergeTokenService, 10 минут), а перенос выполняет только явный
 * POST /identities/merge с этим токеном — после того как человек увидел
 * числа и подтвердил.
 */
@RestController
@RequestMapping("/api/v1/identities")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Identities", description = "Управление способами входа привязанными к учётке")
public class IdentityController {

    private final UserService userService;
    private final TaskService taskService;
    private final LoginCodeService loginCodeService;
    private final EmailSender emailSender;
    private final TelegramLoginWidgetValidator loginWidgetValidator;
    private final AccountTransferService accountTransferService;
    private final MergeTokenService mergeTokenService;

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
            description = "Привязывает почту к текущей учётке; 409, если почта уже принадлежит другой — с токеном для /merge")
    public ResponseEntity<?> confirmEmail(@Valid @RequestBody VerifyCodeRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        if (!loginCodeService.verifyCode(request.email(), request.code())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid code");
        }
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);
        return bindOrConflict(user.userId(), IdentityProvider.EMAIL, normalizedEmail);
    }

    @PostMapping("/telegram")
    @Operation(summary = "Привязать Telegram",
            description = "Проверяет данные Login Widget; 409, если Telegram уже привязан к другой учётке — с токеном для /merge")
    public ResponseEntity<?> bindTelegram(@Valid @RequestBody TelegramLoginWidgetAuthRequest request,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        if (!loginWidgetValidator.validate(request.fields())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login widget data");
        }
        Map<String, String> f = request.fields();
        return bindOrConflict(user.userId(), IdentityProvider.TELEGRAM, f.get("id"));
    }

    @PostMapping("/merge")
    @Operation(summary = "Подтвердить перенос данных",
            description = "Завершает привязку по одноразовому токену согласия из 409-ответа confirm/telegram")
    public IdentityBindResponse merge(@Valid @RequestBody MergeRequest request,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        var data = mergeTokenService.consume(request.token())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Токен слияния недействителен, истёк или уже использован"));
        if (!data.to().equals(user.userId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Токен слияния выдан другой учётке");
        }
        var transferred = accountTransferService.transfer(data.from(), data.to());
        var identity = userService.bindIdentity(data.to(), data.provider(), data.externalId());
        return new IdentityBindResponse(identity, transferred);
    }

    @DeleteMapping("/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отвязать способ входа", description = "Нельзя отвязать последний способ")
    public void unbind(@PathVariable IdentityProvider provider, @AuthenticationPrincipal AuthenticatedUser user) {
        userService.unbindIdentity(user.userId(), provider);
    }

    private ResponseEntity<?> bindOrConflict(UUID userId, IdentityProvider provider, String externalId) {
        var owner = userService.findIdentityOwner(provider, externalId);
        if (owner.isPresent() && !owner.get().equals(userId)) {
            var summary = taskService.countOwnership(owner.get());
            String token = mergeTokenService.issue(owner.get(), userId, provider, externalId);
            var body = new MergeConflictResponse(summary.tasks(), summary.groups(), summary.tags(), token);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        var identity = userService.bindIdentity(userId, provider, externalId);
        return ResponseEntity.ok(new IdentityBindResponse(identity, null));
    }
}
