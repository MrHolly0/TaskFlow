package ru.taskflow.app.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.taskflow.app.application.MergeTokenService;
import ru.taskflow.app.application.PhoneInboundWebhookRateLimiter;
import ru.taskflow.app.web.dto.UcallerInboundWebhookRequest;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.user.api.IdentityProvider;
import ru.taskflow.user.api.UserProfile;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.PhoneInboundConfirmationService;
import ru.taskflow.user.application.PhoneNumberNormalizer;
import ru.taskflow.user.application.RefreshTokenService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Приём подтверждения номера от Ucaller. Подписи запроса у метода в
 * документации нет — вся защита на нашей стороне, и это защита от захвата
 * учётной записи, а не формальность. Обязательно всё сразу:
 *
 * 1. Неугадываемый секретный отрезок пути — сверяется в постоянное время
 *    ({@link MessageDigest#isEqual}), не совпал — 404, а не 403: посторонним
 *    не подтверждаем даже существование точки.
 * 2. Подтверждаем только по ожидающей записи — нет записи на номер, молча
 *    отбрасываем.
 * 3. Сверяем confirmationNumber с тем, что сохранили при запросе — обе
 *    стороны нормализуем тем же нормализатором, что и clientNumber: Ucaller
 *    не гарантирует одинаковый вид номера в ответе inboundCallWaiting и в
 *    самом вебхуке.
 * 4. clientNumber нормализуем в E.164 тем же нормализатором, что и everywhere
 *    else, и сверяем с номером записи (запись уже ключ по этому номеру —
 *    сверка встроена в сам поиск).
 * 5. Одноразовость — PhoneInboundConfirmationService.consumePending уже
 *    делает getAndDelete, повторная доставка того же callId (тот же
 *    confirmationNumber) не найдёт запись во второй раз.
 * 6. Ограничение частоты на саму точку приёма.
 *
 * Каждое отклонённое уведомление — в лог с причиной: это единственный след,
 * если секретный адрес всё-таки утечёт.
 */
@RestController
@RequestMapping("/api/v1/phone/inbound-webhook")
@Slf4j
@Tag(name = "Phone inbound webhook", description = "Приём подтверждения номера от Ucaller (внутренняя точка)")
public class PhoneInboundWebhookController {

    private final UserService userService;
    private final TaskService taskService;
    private final MergeTokenService mergeTokenService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final PhoneInboundConfirmationService phoneInboundConfirmationService;
    private final PhoneInboundWebhookRateLimiter rateLimiter;
    private final String configuredSecret;

    public PhoneInboundWebhookController(
            UserService userService,
            TaskService taskService,
            MergeTokenService mergeTokenService,
            JwtService jwtService,
            RefreshTokenService refreshTokenService,
            PhoneInboundConfirmationService phoneInboundConfirmationService,
            PhoneInboundWebhookRateLimiter rateLimiter,
            @Value("${app.ucaller.callback-secret:}") String configuredSecret) {
        this.userService = userService;
        this.taskService = taskService;
        this.mergeTokenService = mergeTokenService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.phoneInboundConfirmationService = phoneInboundConfirmationService;
        this.rateLimiter = rateLimiter;
        this.configuredSecret = configuredSecret;
    }

    @PostMapping("/{secret}")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Уведомление о входящем звонке", description = "Вызывается Ucaller, не человеком")
    public void handleInboundCall(@PathVariable String secret,
                                   @RequestBody(required = false) UcallerInboundWebhookRequest request,
                                   HttpServletRequest httpRequest) {
        if (!secretMatches(secret)) {
            // 404, не 403 — не подтверждаем посторонним даже существование
            // точки. Мимо лога: путь с чужим секретом ничего не доказывает,
            // разве что кто-то перебирает — это накрывает лимитер ниже.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        if (!rateLimiter.allow(clientIp(httpRequest))) {
            log.warn("Отклонено: превышен лимит частоты на точку приёма вебхука Ucaller (ip={})", clientIp(httpRequest));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        if (request == null || isBlank(request.clientNumber()) || isBlank(request.confirmationNumber())) {
            log.warn("Отклонено: неполное тело уведомления от Ucaller (callId={})", request != null ? request.callId() : null);
            return;
        }

        var normalizedPhone = PhoneNumberNormalizer.normalize(request.clientNumber());
        if (normalizedPhone.isEmpty()) {
            log.warn("Отклонено: clientNumber не приводится к E.164 (callId={}, clientNumber={})",
                    request.callId(), request.clientNumber());
            return;
        }
        String phone = normalizedPhone.get();

        var pending = phoneInboundConfirmationService.consumePending(phone);
        if (pending.isEmpty()) {
            log.warn("Отклонено: нет ожидающей записи на номер (callId={}, phone={})", request.callId(), mask(phone));
            return;
        }

        String storedConfirmation = pending.get().confirmationNumber();
        String receivedConfirmation = request.confirmationNumber();
        // Тот же нормализатор, что и для clientNumber выше — забыли применить его
        // здесь при первой реализации: confirmation_number в вебхуке необязательно
        // приходит в том же виде, что и в ответе inboundCallWaiting, откуда взят
        // сохранённый номер.
        String normalizedStored = PhoneNumberNormalizer.normalize(storedConfirmation).orElse(storedConfirmation);
        String normalizedReceived = PhoneNumberNormalizer.normalize(receivedConfirmation).orElse(receivedConfirmation);
        if (!normalizedStored.equals(normalizedReceived)) {
            log.warn("Отклонено: confirmationNumber не совпал с ожидающей записью "
                            + "(callId={}, phone={}, сохранённый={} -> {}, пришедший={} -> {})",
                    request.callId(), mask(phone), storedConfirmation, normalizedStored,
                    receivedConfirmation, normalizedReceived);
            return;
        }

        UUID boundUserId = pending.get().boundUserId();
        if (boundUserId == null) {
            completeLogin(phone);
        } else {
            completeBind(phone, boundUserId);
        }
        log.info("Подтверждён входящий звонок (callId={}, ucallerId={}, phone={})",
                request.callId(), pending.get().ucallerId(), mask(phone));
    }

    private void completeLogin(String phone) {
        var dto = userService.findOrCreateByIdentity(IdentityProvider.PHONE, phone, new UserProfile(null, null, null, null));
        String accessToken = jwtService.issueAccessToken(dto.id(), dto.username());
        String refreshToken = refreshTokenService.issue(dto.id());
        phoneInboundConfirmationService.storeLoginResult(phone, accessToken, refreshToken);
    }

    private void completeBind(String phone, UUID userId) {
        var owner = userService.findIdentityOwner(IdentityProvider.PHONE, phone);
        if (owner.isPresent() && !owner.get().equals(userId)) {
            var summary = taskService.countOwnership(owner.get());
            String mergeToken = mergeTokenService.issue(owner.get(), userId, IdentityProvider.PHONE, phone);
            phoneInboundConfirmationService.storeConflictResult(phone, summary.tasks(), summary.groups(), summary.tags(), mergeToken);
            return;
        }
        userService.bindIdentity(userId, IdentityProvider.PHONE, phone);
        phoneInboundConfirmationService.storeBindResult(phone, OffsetDateTime.now());
    }

    private boolean secretMatches(String secret) {
        if (configuredSecret == null || configuredSecret.isBlank() || secret == null) {
            return false;
        }
        return MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8),
                configuredSecret.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp : request.getRemoteAddr();
    }

    // Не светим номер целиком в логах — только последние четыре цифры, как
    // в собственных ответах Ucaller (маска в их же confirmation_number).
    private String mask(String phoneE164) {
        return phoneE164.length() > 4 ? "***" + phoneE164.substring(phoneE164.length() - 4) : phoneE164;
    }
}
