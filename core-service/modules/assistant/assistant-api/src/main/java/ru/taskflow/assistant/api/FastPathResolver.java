package ru.taskflow.assistant.api;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Экономия на пути с самым дешёвым выигрышем: нажатие кнопки и короткое
 * подтверждение не должны стоить вызова модели. Срабатывает только когда есть
 * незакрытое предложение — «готово» вне контекста ничего не значит и должно
 * уйти в обычный цикл агента, а не молча потеряться.
 *
 * Живёт в assistant-api, а не assistant-impl: нужен любому каналу (Telegram,
 * позже miniapp), а модули-impl не зависят друг от друга — только от чужих api.
 */
@Component
public class FastPathResolver {

    private static final Set<String> CONFIRMATIONS = Set.of("да", "+", "ок", "окей", "готово", "применяй", "давай");
    private static final Set<String> REJECTIONS = Set.of("нет", "-", "отмена", "отмени", "не надо");
    private static final int MAX_LENGTH = 20;

    /**
     * Пусто — быстрый путь не применяется, обращение уходит в цикл агента.
     * true/false — подтверждение или отказ от незакрытого предложения.
     */
    public Optional<Boolean> resolve(String text, boolean hasPendingProposal) {
        if (!hasPendingProposal || text == null) {
            return Optional.empty();
        }

        String normalized = normalize(text);
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            return Optional.empty();
        }

        if (CONFIRMATIONS.contains(normalized)) {
            return Optional.of(true);
        }
        if (REJECTIONS.contains(normalized)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[.,!?;:]+$", "");
    }
}
