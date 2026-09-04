package ru.taskflow.task.application;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * §5.4: узнаёт по названию, привязана ли задача к часам работы учреждения —
 * банка, поликлиники и подобных. Сопоставление по корню слова (роли —
 * настроенные строки из FocusHoursGateConfig), не по словарю целых слов:
 * так ловятся падежные формы («в поликлинику», «из поликлиники») без
 * полноценной морфологии.
 * <p>
 * Корень «поли» склеил бы «поликлинику» с «полить»/«поливать» — отсюда
 * требование к длине корня в самой конфигурации (см. application.yml),
 * не здесь: этот класс сравнивает ровно то, что ему дали.
 */
final class TitleHoursGateMatcher {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TitleHoursGateMatcher() {}

    static boolean tiedToHours(String title, List<String> roots) {
        if (title == null || roots == null || roots.isEmpty()) {
            return false;
        }
        String normalized = normalize(title);
        if (normalized.isBlank()) {
            return false;
        }
        for (String word : normalized.split(" ")) {
            for (String root : roots) {
                if (!root.isBlank() && word.startsWith(root.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalize(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        String stripped = NON_WORD.matcher(lower).replaceAll(" ");
        return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
    }
}
