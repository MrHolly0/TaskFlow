package ru.taskflow.nlp.api;

import java.util.List;

/**
 * failed различает два непохожих исхода: пустой toolCalls — модель ничего
 * не предложила, это нормально; failed=true — модель недоступна (автомат
 * разомкнут или попытки исчерпаны), и вызывающая сторона обязана сохранить
 * реплику пользователя сама, а не тихо её потерять.
 * <p>
 * Фабрика называется unavailable(), а не failed(): у record-компонента
 * failed уже есть неявный accessor boolean failed(), и статический метод
 * с тем же именем и без параметров с ним не компилируется — это ограничение
 * языка, а не выбор дизайна.
 */
public record LlmToolResponse(
        List<LlmToolCall> toolCalls,
        String text,
        int inputTokens,
        int outputTokens,
        boolean failed
) {
    public static LlmToolResponse unavailable() {
        return new LlmToolResponse(List.of(), null, 0, 0, true);
    }
}
