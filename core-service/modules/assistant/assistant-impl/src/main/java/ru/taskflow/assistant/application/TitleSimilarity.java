package ru.taskflow.assistant.application;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Мера пересечения слов между двумя названиями — раньше жила только в
 * DuplicateGuard, теперь нужна и в AgentLoop для того же вопроса на другом
 * пороге: не «это дубль?», а «это ещё и другая задача?». Порог у каждого
 * потребителя свой, здесь только сама мера.
 */
@Component
public class TitleSimilarity {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String normalize(String title) {
        if (title == null) {
            return "";
        }
        String lower = title.toLowerCase(Locale.ROOT);
        String stripped = NON_WORD.matcher(lower).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }

    public Set<String> wordsOf(String normalized) {
        if (normalized.isBlank()) {
            return Set.of();
        }
        // Set.of(array) падает на повторяющемся слове (естественно для устной речи) — здесь дубли не ошибка.
        return new HashSet<>(Arrays.asList(WHITESPACE.split(normalized)));
    }

    public double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        long intersection = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    /** Удобство для одноразового сравнения: нормализация + жаккар в один вызов. */
    public double similarity(String a, String b) {
        String normalizedA = normalize(a);
        String normalizedB = normalize(b);
        if (normalizedA.equals(normalizedB)) {
            return 1.0;
        }
        return jaccard(wordsOf(normalizedA), wordsOf(normalizedB));
    }
}
