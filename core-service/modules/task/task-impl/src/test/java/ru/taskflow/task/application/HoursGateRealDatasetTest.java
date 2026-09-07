package ru.taskflow.task.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.4: проверка правила на настоящих названиях, а не на выдуманных. Набор
 * размечен отдельно и заранее (docs/vkr/focus/hours-gate-labels.json) — этот
 * тест его не создаёт и не правит, только читает. Набор за пределами
 * репозитория (в нём настоящие личные названия, см. CLAUDE.md о приватности)
 * и в git не попадает, поэтому тест сам себя пропускает, если файла нет —
 * тот же приём, что у ExperimentRunner с датасетами реплик.
 */
class HoursGateRealDatasetTest {

    // Должно совпадать с core-service/app/src/main/resources/application.yml,
    // app.focus.hours-gate.roots — тест не поднимает Spring-контекст, чтобы
    // оставаться быстрым и не тянуть Postgres, поэтому список продублирован
    // здесь явно, а не прочитан из конфигурации.
    private static final List<String> ROOTS = List.of(
            "поликлин", "больниц", "аптек", "магаз", "мфц", "нотар", "автосерв",
            "химчист", "загс", "соцзащит", "полиц", "консульст", "ветеринар");

    @Test
    void ruleProducesZeroFalsePositivesOnRealTitles() throws IOException {
        Path file = repoRoot().resolve("docs/vkr/focus/hours-gate-labels.json");
        Assumptions.assumeTrue(Files.exists(file),
                "Размеченный набор не найден (" + file + ") — тест пропущен, он вне git");

        List<HoursGateLabelRow> rows = new ObjectMapper()
                .readValue(file.toFile(), new TypeReference<List<HoursGateLabelRow>>() {});

        List<String> falsePositives = rows.stream()
                .filter(r -> !r.junk() && Boolean.FALSE.equals(r.tiedToHours()))
                .filter(r -> TitleHoursGateMatcher.tiedToHours(r.title(), ROOTS))
                .map(HoursGateLabelRow::title)
                .toList();
        assertThat(falsePositives)
                .as("ложные срабатывания правила на задачах без привязки к часам работы")
                .isEmpty();

        long positives = rows.stream().filter(r -> !r.junk() && Boolean.TRUE.equals(r.tiedToHours())).count();
        long caught = rows.stream()
                .filter(r -> !r.junk() && Boolean.TRUE.equals(r.tiedToHours()))
                .filter(r -> TitleHoursGateMatcher.tiedToHours(r.title(), ROOTS))
                .count();
        System.out.printf(
                "§5.4 на размеченном наборе: привязанных %d, поймано правилом %d, ложных непопаданий %d, ложных срабатываний %d%n",
                positives, caught, positives - caught, falsePositives.size());
    }

    private Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Не нашёл settings.gradle.kts ни в одном из родительских каталогов");
    }
}
