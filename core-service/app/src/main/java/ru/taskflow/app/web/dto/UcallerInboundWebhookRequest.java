package ru.taskflow.app.web.dto;

/**
 * Тело POST-уведомления от Ucaller на callback_url из inboundCallWaiting.
 * Поля названы точно как в документации Ucaller (developer.ucaller.ru),
 * не переименовываем под свой стиль — так проще сверять с реальным трафиком.
 */
public record UcallerInboundWebhookRequest(
        String callId,
        String clientNumber,
        String confirmationNumber,
        Boolean isMnp,
        String operatorName,
        String operatorNameMnp,
        String regionName
) {
}
