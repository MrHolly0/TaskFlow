package ru.taskflow.app.experiment;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gradle запускает тесты каждого модуля с рабочим каталогом внутри самого
 * модуля (для core-service:app это core-service/app/), а не в корне
 * репозитория — путь "docs/vkr/..." без поправки резолвится в
 * core-service/app/docs/vkr/... и никогда не находится. Здесь и только
 * здесь эта поправка сделана один раз.
 * <p>
 * Корень определяется по маркеру settings.gradle.kts, а не по «первому
 * найденному совпадению пути» — так это работает одинаково что для файла,
 * который уже существует (датасет), что для каталога, который ещё нет и
 * будет создан только что (выгрузка результатов).
 */
final class RepoPaths {

    private static final String ROOT_MARKER = "settings.gradle.kts";

    private RepoPaths() {}

    static Path resolveFromRepoRoot(String relativePath) {
        Path given = Path.of(relativePath);
        if (given.isAbsolute()) {
            return given;
        }
        return repoRoot().resolve(given).normalize();
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(ROOT_MARKER))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Не нашёл " + ROOT_MARKER + " ни в одном из родительских каталогов "
                + "относительно " + Path.of("").toAbsolutePath() + " — корень репозитория не определён");
    }
}
