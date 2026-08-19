package ru.taskflow.user.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.shared.security.TelegramInitDataValidator;
import ru.taskflow.shared.security.TelegramLoginWidgetValidator;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.AuthRateLimiter;
import ru.taskflow.user.application.EmailSender;
import ru.taskflow.user.application.LoginCodeService;
import ru.taskflow.user.application.RefreshTokenService;
import ru.taskflow.user.infrastructure.web.dto.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Auth", description = "Авторизация через Telegram и управление токенами")
public class AuthController {

    private final TelegramInitDataValidator initDataValidator;
    private final TelegramLoginWidgetValidator loginWidgetValidator;
    private final JwtService jwtService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final LoginCodeService loginCodeService;
    private final EmailSender emailSender;
    private final AuthRateLimiter rateLimiter;

    @PostMapping("/telegram-miniapp")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Авторизация Mini App", description = "Проверяет подпись initData и выдаёт JWT токены")
    public AuthResponse miniAppAuth(@Valid @RequestBody TelegramMiniAppAuthRequest request, HttpServletRequest httpRequest) {
        requireWithinRateLimit(httpRequest);
        if (!initDataValidator.validate(request.initData())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid initData");
        }
        var user = parseMiniAppUser(request.initData());
        var dto = userService.findOrCreateByTelegram(
                user.id(), user.username(), user.firstName(), user.lastName());
        return issueTokens(dto.id(), dto.username());
    }

    @PostMapping("/telegram-login")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Авторизация через Telegram Login Widget", description = "Проверяет данные Login Widget и выдаёт JWT токены")
    public AuthResponse loginWidgetAuth(@Valid @RequestBody TelegramLoginWidgetAuthRequest request, HttpServletRequest httpRequest) {
        requireWithinRateLimit(httpRequest);
        if (!loginWidgetValidator.validate(request.fields())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login widget data");
        }
        Map<String, String> f = request.fields();
        long telegramId = Long.parseLong(f.get("id"));
        var dto = userService.findOrCreateByTelegram(
                telegramId, f.get("username"), f.get("first_name"), f.get("last_name"));
        return issueTokens(dto.id(), dto.username());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Обновить токен", description = "Выдаёт новые access и refresh токены")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest httpRequest) {
        requireWithinRateLimit(httpRequest);
        UUID userId = refreshTokenService.resolve(request.refreshToken())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token"));
        refreshTokenService.revoke(request.refreshToken());
        var dto = userService.findById(userId);
        return issueTokens(dto.id(), dto.username());
    }

    @PostMapping("/email/request-code")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Запросить код входа на почту",
            description = "Отвечает одинаково независимо от того, известен адрес или дошло ли письмо")
    public void requestCode(@Valid @RequestBody RequestCodeRequest request, HttpServletRequest httpRequest) {
        requireWithinRateLimit(httpRequest);
        String code = loginCodeService.issueCode(request.email());
        try {
            emailSender.sendLoginCode(request.email(), code);
            loginCodeService.confirmIssued(request.email(), code);
        } catch (RuntimeException e) {
            log.warn("Не удалось отправить код входа: {}", e.getMessage());
        }
    }

    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Подтвердить код и войти", description = "Создаёт учётку по идентичности EMAIL, если её ещё нет")
    public AuthResponse verifyCode(@Valid @RequestBody VerifyCodeRequest request, HttpServletRequest httpRequest) {
        requireWithinRateLimit(httpRequest);
        if (!rateLimiter.allowForEmailConfirm(httpRequest, request.email())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "слишком много попыток, попробуйте позже");
        }
        if (!loginCodeService.verifyCode(request.email(), request.code())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid code");
        }
        String normalizedEmail = request.email().toLowerCase(Locale.ROOT);
        String localPart = normalizedEmail.substring(0, normalizedEmail.indexOf('@'));
        var dto = userService.findOrCreateByIdentity(
                IdentityProvider.EMAIL, normalizedEmail, new UserProfile(localPart, null, null, null));
        return issueTokens(dto.id(), dto.username());
    }

    private void requireWithinRateLimit(HttpServletRequest httpRequest) {
        if (!rateLimiter.allow(httpRequest)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "слишком много попыток входа, попробуйте позже");
        }
    }

    private AuthResponse issueTokens(UUID userId, String username) {
        String accessToken = jwtService.issueAccessToken(userId, username);
        String refreshToken = refreshTokenService.issue(userId);
        return new AuthResponse(accessToken, refreshToken);
    }

    private record TelegramUser(long id, String username, String firstName, String lastName) {}

    private TelegramUser parseMiniAppUser(String initData) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : initData.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            params.put(pair.substring(0, idx), URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        String userJson = params.getOrDefault("user", "{}");
        long id = extractJsonLong(userJson, "id");
        String username = extractJsonString(userJson, "username");
        String firstName = extractJsonString(userJson, "first_name");
        String lastName = extractJsonString(userJson, "last_name");
        return new TelegramUser(id, username, firstName, lastName);
    }

    private long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Long.parseLong(json.substring(start, end)); } catch (NumberFormatException e) { return 0; }
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? null : json.substring(start, end);
    }
}