package ru.taskflow.notificationworker.application;

import java.net.URI;

/**
 * Определяет push-службу по домену endpoint подписки — чтобы в логе после
 * «принято» было видно, кто отвечает за доставку дальше. Сам endpoint в лог
 * не идёт: это фактически предъявительский токен на конкретное устройство.
 */
final class PushServiceIdentifier {

    private PushServiceIdentifier() {
    }

    static String nameFor(String endpoint) {
        String host;
        try {
            host = URI.create(endpoint).getHost();
        } catch (Exception e) {
            return "неизвестная служба";
        }
        if (host == null) {
            return "неизвестная служба";
        }
        if (host.endsWith("googleapis.com")) {
            return "Google";
        }
        if (host.endsWith("mozilla.com")) {
            return "Mozilla";
        }
        if (host.endsWith("apple.com")) {
            return "Apple";
        }
        return "неизвестная служба (" + host + ")";
    }
}
