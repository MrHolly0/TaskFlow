package ru.taskflow.user.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.RefreshTokenService;
import ru.taskflow.user.infrastructure.web.dto.AuthResponse;

import java.util.Map;

/**
 * Доступен только при активном профиле dev. В остальных окружениях бин не создаётся,
 * и путь /api/v1/auth/dev-token отсутствует.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Profile("dev")
@Tag(name = "Auth", description = "Авторизация через Telegram и управление токенами")
public class DevAuthController {

    private final JwtService jwtService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/dev-token")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Выдать тестовый токен", description = "Только для локальной разработки")
    public AuthResponse devToken(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "dev_user");
        long fakeTelegramId = -1_000_000_000L - Math.abs((long) username.hashCode() % 1_000_000_000L);
        var dto = userService.findOrCreateByTelegram(fakeTelegramId, username, "Dev", "User");
        String accessToken = jwtService.issueAccessToken(dto.id(), dto.username());
        String refreshToken = refreshTokenService.issue(dto.id());
        return new AuthResponse(accessToken, refreshToken);
    }
}
