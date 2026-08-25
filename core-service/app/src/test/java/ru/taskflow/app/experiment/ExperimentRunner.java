package ru.taskflow.app.experiment;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantEntryPoint;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.ProposalStatus;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.user.api.UserService;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Стенд эксперимента Б1–Б4 ТЗ (docs/vkr/тз-агенту-сбор-данных.md): один
 * проход по датасету собирает все показатели сразу — разбор, задержки,
 * токены, вызовы инструментов, отсев фильтров, признаки двоякости.
 * Отдельного прогона на каждый показатель НЕТ намеренно — при 8000 TPM
 * Groq пятикратный сбор кладёт бюджет квоты, который и так на исходе.
 * <p>
 * Ретраев на отдельной (строка, попытка) НЕТ: цель — не скрыть срабатывания
 * предела частоты повтором, а честно посчитать, сколько их было (пункт
 * "остановись и доложи" Задачи 3). Пауза PACING_MS между обращениями —
 * единственная защита от 429, и это пауза, а не повторная попытка.
 * <p>
 * Путь к датасету и число повторов — извне (система/переменная окружения),
 * без пересборки: -Dexperiment.dataset=/путь/файл.json -Dexperiment.repeats=3
 * или EXPERIMENT_DATASET / EXPERIMENT_REPEATS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("live")
class ExperimentRunner {

    private static final long PACING_MS = 20_000L;
    private static final Pattern DETERMINISTIC_AMBIGUITY =
            Pattern.compile("^реплика могла означать «.*», а могла — новую задачу$");
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AssistantService assistantService;
    @Autowired
    private UserService userService;
    @Autowired
    private TaskService taskService;

    @BeforeAll
    static void requiresLiveGroqKey() {
        String key = System.getenv("GROQ_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(),
                "GROQ_API_KEY не задан — стенд эксперимента пропущен");
    }

    @Test
    void runExperiment() throws InterruptedException {
        Path datasetPath = RepoPaths.resolveFromRepoRoot(
                configValue("experiment.dataset", "EXPERIMENT_DATASET", "docs/vkr/dataset/nlp-dataset.json"));
        int repeats = Integer.parseInt(configValue("experiment.repeats", "EXPERIMENT_REPEATS", "3"));

        List<DatasetRow> dataset = DatasetReader.read(datasetPath);
        UUID userId = newExperimentUser();
        ActionMatcher matcher = new ActionMatcher();
        List<ExperimentRunResult> results = new ArrayList<>();

        boolean first = true;
        for (int attempt = 1; attempt <= repeats; attempt++) {
            for (DatasetRow row : dataset) {
                if (!first) {
                    Thread.sleep(PACING_MS);
                }
                first = false;
                results.add(runOneRow(userId, row, attempt, matcher));
            }
        }

        Path outDir = RepoPaths.resolveFromRepoRoot(
                configValue("experiment.outputDir", "EXPERIMENT_OUTPUT_DIR", "docs/vkr/dataset/результаты"));
        String stamp = OffsetDateTime.now(ZONE).format(STAMP);
        ExperimentOutputWriter.writeCsv(results, outDir.resolve("run-" + stamp + ".csv"));
        ExperimentOutputWriter.writeJson(results, outDir.resolve("run-" + stamp + ".json"));

        long rateLimited = results.stream().filter(ExperimentRunResult::llmFailed).count();
        System.out.printf(
                "Готово: %d обращений (%d реплик × %d прогона), деградаций/предела частоты: %d, файлы в %s%n",
                results.size(), dataset.size(), repeats, rateLimited, outDir.toAbsolutePath());

        assertThat(results).hasSize(dataset.size() * repeats);
    }

    private ExperimentRunResult runOneRow(UUID userId, DatasetRow row, int attempt, ActionMatcher matcher) {
        Map<String, UUID> setupRefToTaskId = new HashMap<>();
        try {
            for (SetupTaskSpec spec : row.setupTasks()) {
                TaskResponse created = taskService.create(userId, new CreateTaskRequest(
                        spec.title(), null,
                        spec.priority() == null ? null : TaskPriority.valueOf(spec.priority().toUpperCase()),
                        spec.deadlineOffsetDays() == null ? null
                                : OffsetDateTime.now(ZONE).plusDays(spec.deadlineOffsetDays()),
                        null, spec.group(), null, null, TaskSource.MANUAL));
                setupRefToTaskId.put(spec.ref(), created.id());
            }

            Proposal proposal = assistantService.handleText(userId, row.text(), AssistantChannel.WEB,
                    AssistantEntryPoint.CHAT);

            return toResult(row, attempt, proposal, setupRefToTaskId, matcher);
        } catch (Exception e) {
            return errorResult(row, attempt, e);
        } finally {
            cleanUp(userId);
        }
    }

    private ExperimentRunResult toResult(DatasetRow row, int attempt, Proposal proposal,
                                          Map<String, UUID> setupRefToTaskId, ActionMatcher matcher) {
        boolean statusFailed = proposal.status() == ProposalStatus.FAILED;
        // Живой дефект (эксперимент Б, 50×3 21.08.2026): исчерпание дневного
        // лимита токенов у поставщика (TPD, не TPM — см. отчёт) внутри
        // nlp-worker перехватывается FallbackToolCallProvider и превращается
        // в формально успешный пустой ответ (см. javadoc там же — так и
        // задумано, чтобы отличать «модель промолчала» от сбоя провайдера, но
        // здесь оба случая неотличимы снаружи). AgentLoop видит пустой ответ
        // как «модель ничего не предложила» и синтезирует запасное создание
        // из сырого текста — proposal.status() остаётся PENDING, llmFailed()
        // не срабатывает. inputTokens=0 для настоящего ответа модели
        // невозможен (системный промпт один — уже больше тысячи токенов),
        // так что это надёжный признак деградации, не эвристика на удачу.
        boolean zeroTokens = proposal.inputTokens() == 0 && proposal.outputTokens() == 0;
        boolean llmFailed = statusFailed || zeroTokens;
        ActionMatcher.MatchResult match = llmFailed
                ? matcher.match(List.of(), row.expected(), setupRefToTaskId, LocalDate.now(ZONE), ZONE)
                : matcher.match(proposal.actions(), row.expected(), setupRefToTaskId, LocalDate.now(ZONE), ZONE);

        boolean deterministic = isDeterministicAmbiguity(proposal.ambiguityReason());
        boolean modelMarked = proposal.exclusive() && !deterministic;

        return new ExperimentRunResult(
                row.id(), row.category(), attempt, row.text(),
                match.expectedCount(), match.actualCount(), match.countCorrect(), match.fullyCorrect(),
                match.missingCount(), match.extraCount(),
                match.matched().stream().mapToInt(p -> p.attributeMismatches().size()).sum(),
                proposal.actions().stream().map(a -> a.type().name()).collect(Collectors.joining(";")),
                String.join(" | ", match.missingDescriptions()),
                String.join(" | ", match.extraDescriptions()),
                match.matched().stream().flatMap(p -> p.attributeMismatches().stream()).collect(Collectors.joining(" | ")),
                match.perTypeBreakdown(),
                proposal.totalLatencyMs(), proposal.firstPassLatencyMs(), proposal.secondPassLatencyMs(),
                ExperimentRunResult.NOT_MEASURED,
                proposal.inputTokens(), proposal.outputTokens(),
                proposal.modelPasses(), proposal.modelPasses() == 2, proposal.actions().size(),
                proposal.rejections().size(), String.join(" | ", proposal.rejections()),
                "AMBIGUOUS".equals(row.category()), proposal.exclusive(), modelMarked, deterministic,
                proposal.ambiguityReason(),
                proposal.status() == null ? null : proposal.status().name(),
                llmFailed, degradationNote(statusFailed, zeroTokens, proposal)
        );
    }

    private String degradationNote(boolean statusFailed, boolean zeroTokens, Proposal proposal) {
        if (statusFailed) {
            return "деградация: " + proposal.clarification();
        }
        if (zeroTokens) {
            return "деградация: нулевой расход токенов при status=" + proposal.status()
                    + " — вероятно исчерпан дневной лимит токенов у поставщика";
        }
        return null;
    }

    private ExperimentRunResult errorResult(DatasetRow row, int attempt, Exception e) {
        return new ExperimentRunResult(
                row.id(), row.category(), attempt, row.text(),
                row.expected().actionCount(), 0, false, false, row.expected().actionCount(), 0, 0,
                "", "", "", "", "",
                ExperimentRunResult.NOT_MEASURED, ExperimentRunResult.NOT_MEASURED,
                ExperimentRunResult.NOT_MEASURED, ExperimentRunResult.NOT_MEASURED,
                0, 0, 0, false, 0, 0, "",
                "AMBIGUOUS".equals(row.category()), false, false, false, null,
                null, true, "исключение: " + e.getClass().getSimpleName() + ": " + e.getMessage()
        );
    }

    private boolean isDeterministicAmbiguity(String reason) {
        return reason != null && DETERMINISTIC_AMBIGUITY.matcher(reason).matches();
    }

    /**
     * Полная очистка после каждой строки, а не только между тремя прогонами:
     * иначе задачи, заведённые под одну строку датасета (setup_tasks), остались
     * бы в окне контекста следующей и сделали бы результаты несопоставимыми —
     * что прямо запрещено ТЗ ("между прогонами восстанавливать исходное
     * состояние задач"). Здесь та же гарантия на каждом шаге, не только на
     * границе прогона.
     */
    private void cleanUp(UUID userId) {
        List<TaskResponse> remaining = taskService.findAssistantContext(userId, 500);
        for (TaskResponse task : remaining) {
            try {
                taskService.delete(userId, task.id());
            } catch (Exception e) {
                System.err.println("Не удалось удалить задачу " + task.id() + " при очистке: " + e.getMessage());
            }
        }
    }

    private UUID newExperimentUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return userService.findOrCreateByTelegram(telegramId, "experiment", "Experiment", "Stand").id();
    }

    private String configValue(String systemProperty, String envVar, String fallback) {
        String value = System.getProperty(systemProperty);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getenv(envVar);
        return value != null && !value.isBlank() ? value : fallback;
    }
}
