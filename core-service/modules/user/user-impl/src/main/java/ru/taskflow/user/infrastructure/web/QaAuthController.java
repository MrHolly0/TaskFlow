package ru.taskflow.user.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.RefreshTokenService;
import ru.taskflow.user.infrastructure.web.dto.AuthResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Единственная постоянная демо-учётка для визуальной проверки (не через
 * dev-token — тот открыт всем при активном профиле dev, и именно это
 * привело к девяти накопленным тестовым аккаунтам). Здесь — фиксированный
 * telegram_id, поэтому повторные вызовы не плодят новых пользователей, и
 * секрет из окружения, известный только владельцу: без совпадения — 404,
 * тот же принцип, что и у ProposalNotFoundException — не подтверждать
 * посторонним даже факт существования точки входа. Профиля не требует —
 * доступна всегда, но бесполезна без секрета, который в .env не коммитится.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Авторизация через Telegram и управление токенами")
public class QaAuthController {

    private static final long QA_TELEGRAM_ID = -1L;
    private static final String QA_USERNAME = "qa_demo";

    private final JwtService jwtService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;
    private final String qaLoginSecret;

    public QaAuthController(
            JwtService jwtService,
            UserService userService,
            RefreshTokenService refreshTokenService,
            @Value("${app.qa-login-secret:}") String qaLoginSecret
    ) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.qaLoginSecret = qaLoginSecret;
    }

    @PostMapping("/qa-login")
    @Operation(summary = "Войти под единственной демо-учёткой", description = "Только по секрету из окружения")
    public ResponseEntity<AuthResponse> qaLogin(@RequestHeader(value = "X-Qa-Secret", required = false) String secret) {
        if (qaLoginSecret.isBlank() || !matches(qaLoginSecret, secret)) {
            return ResponseEntity.notFound().build();
        }
        var dto = userService.findOrCreateByTelegram(QA_TELEGRAM_ID, QA_USERNAME, "QA", "Demo");
        String accessToken = jwtService.issueAccessToken(dto.id(), dto.username());
        String refreshToken = refreshTokenService.issue(dto.id());
        return ResponseEntity.ok(new AuthResponse(accessToken, refreshToken));
    }

    private boolean matches(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }
}
