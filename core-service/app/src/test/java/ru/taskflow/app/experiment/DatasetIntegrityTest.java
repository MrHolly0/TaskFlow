package ru.taskflow.app.experiment;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет реальный датасет Б1 до любого живого обращения — дёшево и не
 * тратит квоту, но ловит структурные ошибки (битые target_ref, неверные
 * типы, разъехавшийся action_count), которые иначе всплыли бы посреди
 * платного прогона.
 * <p>
 * Файл датасета — в docs/vkr/ (приватные материалы диплома, вне git), и
 * тест не должен падать на чужой машине, где его нет: пропускается через
 * Assumptions, тем же приёмом, что GROQ_API_KEY в остальных живых тестах.
 */
class DatasetIntegrityTest {

    private static final Set<String> VALID_TYPES = Set.of("create", "complete", "cancel", "reschedule", "update");

    private static List<DatasetRow> rows;

    @BeforeAll
    static void loadDataset() {
        Path datasetPath = RepoPaths.resolveFromRepoRoot("docs/vkr/dataset/nlp-dataset.json");
        Assumptions.assumeTrue(Files.exists(datasetPath),
                "Датасет " + datasetPath + " не найден — проверка пропущена");
        rows = DatasetReader.read(datasetPath);
    }

    @Test
    void ids_areUnique() {
        Set<String> ids = new HashSet<>();
        for (DatasetRow row : rows) {
            assertThat(ids.add(row.id())).overridingErrorMessage("Повторяющийся id: %s", row.id()).isTrue();
        }
    }

    @Test
    void actionTypes_areAllValid() {
        for (DatasetRow row : rows) {
            for (ExpectedAction action : row.expected().actions()) {
                assertThat(VALID_TYPES)
                        .overridingErrorMessage("Строка %s: неизвестный тип действия %s", row.id(), action.type())
                        .contains(action.type() == null ? null : action.type().toLowerCase());
            }
        }
    }

    @Test
    void targetRef_alwaysResolvesWithinSameRow() {
        for (DatasetRow row : rows) {
            Set<String> setupRefs = new HashSet<>();
            for (SetupTaskSpec spec : row.setupTasks()) {
                setupRefs.add(spec.ref());
            }
            for (ExpectedAction action : row.expected().actions()) {
                if (action.targetRef() != null) {
                    assertThat(setupRefs)
                            .overridingErrorMessage("Строка %s: target_ref %s не найден среди setup_tasks",
                                    row.id(), action.targetRef())
                            .contains(action.targetRef());
                }
            }
        }
    }

    @Test
    void actionCount_matchesActionsListSize() {
        for (DatasetRow row : rows) {
            assertThat(row.expected().actionCount())
                    .overridingErrorMessage("Строка %s: action_count=%d, а элементов actions=%d",
                            row.id(), row.expected().actionCount(), row.expected().actions().size())
                    .isEqualTo(row.expected().actions().size());
        }
    }

    @Test
    void negativeCategory_alwaysExpectsZeroActions() {
        for (DatasetRow row : rows) {
            if ("NEGATIVE".equals(row.category())) {
                assertThat(row.expected().actionCount())
                        .overridingErrorMessage("Строка %s: категория NEGATIVE, но action_count=%d",
                                row.id(), row.expected().actionCount())
                        .isZero();
            }
        }
    }

    @Test
    void categoryDistribution_matchesReportedCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (DatasetRow row : rows) {
            counts.merge(row.category(), 1, Integer::sum);
        }
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("MULTI_CREATE", 20),
                Map.entry("DATE_TIME", 20),
                Map.entry("SINGLE_CREATE", 15),
                Map.entry("UPDATE_EXISTING", 15),
                Map.entry("COMPLETE_CANCEL", 15),
                Map.entry("MIXED_OPERATIONS", 15),
                Map.entry("AMBIGUOUS", 15),
                Map.entry("NEGATIVE", 15),
                Map.entry("VOICE_TRANSCRIPT", 10),
                Map.entry("EDGE_CASE", 10)
        ));
    }

    @Test
    void reportsSummaryCountsForManualCrossCheck() {
        long withSetupTasks = rows.stream().filter(r -> !r.setupTasks().isEmpty()).count();
        long expectingNoActions = rows.stream().filter(r -> r.expected().actionCount() == 0).count();
        long expectingAmbiguous = rows.stream().filter(r -> r.expected().ambiguous()).count();

        System.out.printf("Датасет: %d строк, %d с setup_tasks, %d ожидают ноль действий, %d ожидают двоякость%n",
                rows.size(), withSetupTasks, expectingNoActions, expectingAmbiguous);

        assertThat(rows).hasSize(150);
        assertThat(withSetupTasks).isEqualTo(64);
        // 34 → 21 после переразметки AMBIGUOUS владельцем 25.08.2026: двоякость —
        // это два взаимоисключающих варианта (action_count=2), а не ноль действий;
        // ноль был ошибкой разметки, не поведением системы. AM-03/AM-15 остались
        // с одиночным ожиданием — контроль, что ветка не срабатывает без причины.
        assertThat(expectingNoActions).isEqualTo(21);
        assertThat(expectingAmbiguous).isEqualTo(13);
    }
}
