package ru.taskflow.user.infrastructure.phone;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.taskflow.user.application.PhoneConfirmationRequest;
import ru.taskflow.user.application.PhoneNumberNormalizer;
import ru.taskflow.user.application.PhoneVerificationProvider;

/**
 * Подтверждение номера через Ucaller. Основной способ — входящий звонок
 * ({@link #requestConfirmation}): человек звонит на выданный номер, а не мы
 * ему, и заблокировать исходящий вызов человека некому. Прежний способ —
 * исходящий звонок с кодом ({@link #sendCode}) — оставлен нетронутым, но не
 * используется контроллерами: доставка на реальный номер не работала, хотя
 * Ucaller каждый раз отчитывался об успехе (`call_status: 1`).
 *
 * Как и CountryResolver для GeoLite2: без ключей провайдер не роняет
 * приложение при старте, просто логирует ошибку и остаётся недоступным —
 * вызывающая сторона (AuthController/IdentityController) должна не
 * показывать способ входа по телефону, если {@link #isAvailable()} лжив.
 */
@Component
@Slf4j
public class UcallerPhoneVerificationProvider implements PhoneVerificationProvider, HealthIndicator {

    private static final String CALLBACK_PATH = "/api/v1/phone/inbound-webhook/";

    private final String serviceId;
    private final String secretKey;
    private final String publicBaseUrl;
    private final String callbackSecret;
    private final RestClient restClient;

    public UcallerPhoneVerificationProvider(
            @Value("${app.ucaller.service-id:}") String serviceId,
            @Value("${app.ucaller.secret-key:}") String secretKey,
            @Value("${app.public-base-url:}") String publicBaseUrl,
            @Value("${app.ucaller.callback-secret:}") String callbackSecret,
            @Qualifier("ucallerRestClient") RestClient restClient) {
        this.serviceId = serviceId;
        this.secretKey = secretKey;
        this.publicBaseUrl = publicBaseUrl;
        this.callbackSecret = callbackSecret;
        this.restClient = restClient;
    }

    @PostConstruct
    void logIfUnavailable() {
        if (serviceId == null || serviceId.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.error("UCALLER_SERVICE_ID/UCALLER_SECRET_KEY не заданы — вход по телефону отключён");
        } else if (publicBaseUrl == null || publicBaseUrl.isBlank() || callbackSecret == null || callbackSecret.isBlank()) {
            log.error("PUBLIC_BASE_URL/UCALLER_CALLBACK_SECRET не заданы — Ucaller не сможет уведомить о входящем "
                    + "звонке, вход по телефону отключён");
        }
    }

    @Override
    public boolean isAvailable() {
        return serviceId != null && !serviceId.isBlank()
                && secretKey != null && !secretKey.isBlank()
                && publicBaseUrl != null && !publicBaseUrl.isBlank()
                && callbackSecret != null && !callbackSecret.isBlank();
    }

    @Override
    public PhoneConfirmationRequest requestConfirmation(String phoneE164) {
        if (!isAvailable()) {
            throw new IllegalStateException("Ucaller не настроен");
        }
        String digits = phoneE164.startsWith("+") ? phoneE164.substring(1) : phoneE164;
        String callbackUrl = publicBaseUrl + CALLBACK_PATH + callbackSecret;
        // Без этой строки протухший PUBLIC_BASE_URL после перезапуска ngrok
        // ломает вход молча: Ucaller шлёт подтверждение по старому адресу,
        // никто его не получает, а в логах пусто. Теперь видно, куда позвали.
        log.info("Запрос inboundCallWaiting: callback_url={}", callbackUrl);
        UcallerInboundResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/inboundCallWaiting")
                        .queryParam("service_id", serviceId)
                        .queryParam("key", secretKey)
                        .queryParam("phone", digits)
                        .queryParam("callback_url", callbackUrl)
                        .build())
                .retrieve()
                .body(UcallerInboundResponse.class);
        if (response == null || !response.status() || response.confirmationNumber() == null) {
            String reason = response != null && response.error() != null ? response.error() : "пустой ответ";
            throw new IllegalStateException("Ucaller не принял запрос входящего звонка: " + reason);
        }
        // Ucaller отдаёт confirmation_number без "+" (вид 7XXXXXXXXXX) — набрать
        // такой номер с мобильного нельзя. Приводим тем же нормализатором, что
        // и везде остальном, вместо второго на фронтенде.
        String confirmationNumber = PhoneNumberNormalizer.normalize(response.confirmationNumber())
                .orElseThrow(() -> new IllegalStateException(
                        "Ucaller вернул confirmation_number, не приводимый к E.164: " + response.confirmationNumber()));
        return new PhoneConfirmationRequest(
                confirmationNumber,
                response.ucallerId() != null ? response.ucallerId().toString() : null);
    }

    @Override
    public String sendCode(String phoneE164, String code) {
        if (!isAvailable()) {
            throw new IllegalStateException("Ucaller не настроен");
        }
        String digits = phoneE164.startsWith("+") ? phoneE164.substring(1) : phoneE164;
        UcallerResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/initCall")
                        .queryParam("service_id", serviceId)
                        .queryParam("key", secretKey)
                        .queryParam("phone", digits)
                        .queryParam("code", code)
                        .build())
                .retrieve()
                .body(UcallerResponse.class);
        if (response == null || !response.status()) {
            String reason = response != null && response.error() != null ? response.error() : "пустой ответ";
            throw new IllegalStateException("Ucaller отклонил звонок: " + reason);
        }
        // Код передаётся последними цифрами номера звонящего, а пул таких
        // номеров у Ucaller конечен — даже если по документации наш code
        // обязаны использовать как есть, доверяем только ответу. Иначе при
        // расхождении с реальным поведением все входы по телефону будут
        // молча отваливаться как «пользователь ошибся с кодом».
        if (response.code() == null || response.code().isBlank()) {
            log.warn("Ucaller не вернул код звонка (ucaller_id={}) — используем переданный без подтверждения",
                    response.ucallerId());
            return code;
        }
        if (!response.code().equals(code)) {
            log.warn("Ucaller использовал другой код, чем передан (ucaller_id={}): передали={}, фактически={}",
                    response.ucallerId(), code, response.code());
        }
        return response.code();
    }

    @Override
    public Health health() {
        return isAvailable()
                ? Health.up().build()
                : Health.down().withDetail("reason", "Ucaller не настроен").build();
    }

    private record UcallerResponse(
            boolean status,
            String code,
            @JsonProperty("ucaller_id") Long ucallerId,
            String error
    ) {}

    private record UcallerInboundResponse(
            boolean status,
            @JsonProperty("ucaller_id") Long ucallerId,
            @JsonProperty("confirmation_number") String confirmationNumber,
            String error
    ) {}
}
