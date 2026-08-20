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
import ru.taskflow.user.application.PhoneVerificationProvider;

/**
 * Звонок с кодом в последних цифрах номера через Ucaller. Код передаём
 * готовым (см. LoginCodeService) — initCall умеет генерировать свой, но нам
 * нужен единый механизм хранения/проверки для почты и телефона. Наш code —
 * пожелание, а не гарантия: {@link #sendCode} возвращает код из ответа
 * Ucaller, и хранить нужно именно его (см. {@link PhoneVerificationProvider}).
 *
 * Как и CountryResolver для GeoLite2: без ключей провайдер не роняет
 * приложение при старте, просто логирует ошибку и остаётся недоступным —
 * вызывающая сторона (AuthController/IdentityController) должна не
 * показывать способ входа по телефону, если {@link #isAvailable()} лжив.
 */
@Component
@Slf4j
public class UcallerPhoneVerificationProvider implements PhoneVerificationProvider, HealthIndicator {

    private final String serviceId;
    private final String secretKey;
    private final RestClient restClient;

    public UcallerPhoneVerificationProvider(
            @Value("${app.ucaller.service-id:}") String serviceId,
            @Value("${app.ucaller.secret-key:}") String secretKey,
            @Qualifier("ucallerRestClient") RestClient restClient) {
        this.serviceId = serviceId;
        this.secretKey = secretKey;
        this.restClient = restClient;
    }

    @PostConstruct
    void logIfUnavailable() {
        if (!isAvailable()) {
            log.error("UCALLER_SERVICE_ID/UCALLER_SECRET_KEY не заданы — вход по телефону отключён");
        }
    }

    @Override
    public boolean isAvailable() {
        return serviceId != null && !serviceId.isBlank() && secretKey != null && !secretKey.isBlank();
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
}
