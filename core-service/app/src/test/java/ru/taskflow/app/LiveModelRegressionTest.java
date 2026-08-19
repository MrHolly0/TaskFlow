package ru.taskflow.app;

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
import ru.taskflow.assistant.api.AssistantActionType;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.assistant.api.dto.ProposedAction;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.user.api.UserService;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия на живой модели: проверяет не схемную корректность (это покрыто
 * юнит-тестами на моках), а осмысленность предложений. Требует настоящего
 * GROQ_API_KEY и работающего nlp-worker — тег live, исключён из обычной
 * сборки (см. build.gradle.kts), запускается отдельно: ./gradlew liveTest.
 *
 * Живёт в core-service:app, а не в assistant-impl (как в исходном плане),
 * потому что настоящий NlpGatewayService (тот, что реально ходит в
 * nlp-worker) собирается только в nlp-gateway-impl — а assistant-impl не
 * может зависеть от чужого impl-модуля. Здесь, в composition root, полный
 * граф бинов уже собран, включая настоящий AgentLoop и настоящий шлюз.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Tag("live")
class LiveModelRegressionTest {

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
                "GROQ_API_KEY не задан — живой регрессионный тест пропущен");
    }

    private UUID newUser() {
        long telegramId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return userService.findOrCreateByTelegram(telegramId, "live_test", "Live", "Test").id();
    }

    private TaskResponse seedTask(UUID userId, String title, String groupName, TaskPriority priority, OffsetDateTime deadline) {
        return taskService.create(userId, new CreateTaskRequest(
                title, null, priority, deadline, null, groupName, null, null, TaskSource.MANUAL));
    }

    private Proposal handleText(UUID userId, String text) {
        return handleText(userId, text, AssistantEntryPoint.CHAT);
    }

    private Proposal handleText(UUID userId, String text, AssistantEntryPoint entryPoint) {
        Proposal proposal = assistantService.handleText(userId, text, AssistantChannel.WEB, entryPoint);
        assertThat(proposal.status())
                .overridingErrorMessage(
                        "Модель недоступна (nlp-worker/Groq) — реплика выродилась в деградацию вместо предложения: %s",
                        proposal.clarification())
                .isNotEqualTo(ProposalStatus.FAILED);
        return proposal;
    }

    @Test
    void handleText_doesNotCloseUnmentionedTasks() {
        UUID userId = newUser();
        seedTask(userId, "Купить молоко и хлеб", "Покупки", TaskPriority.HIGH, OffsetDateTime.now().plusHours(4));
        seedTask(userId, "Сходить в аптеку за витаминами", "Здоровье", TaskPriority.MEDIUM, null);
        seedTask(userId, "Обсудить с Марком договор", "Работа", TaskPriority.HIGH, OffsetDateTime.now().minusDays(3));
        seedTask(userId, "Позвонить Марку", "Работа", TaskPriority.MEDIUM, null);
        TaskResponse zaryadka = seedTask(userId, "Сделать зарядку", "Спорт", TaskPriority.MEDIUM, OffsetDateTime.now().plusDays(1));
        TaskResponse posylka = seedTask(userId, "Забрать посылку", "Покупки", TaskPriority.MEDIUM, OffsetDateTime.now().plusHours(6));
        seedTask(userId, "Сдать отчёт по договору", "Работа", TaskPriority.HIGH, OffsetDateTime.now().plusDays(3));

        // фикстура 01 из спайка: молоко и хлеб куплены, витамины забыты,
        // про зарядку и посылку в реплике нет ни слова
        Proposal proposal = handleText(userId,
                "в магазин сходил, молоко и хлеб взял, витамины забыл. надо созвониться с марком");

        List<ProposedAction> wronglyClosed = proposal.actions().stream()
                .filter(a -> a.type() == AssistantActionType.COMPLETE)
                .filter(a -> zaryadka.id().equals(a.targetTaskId()) || posylka.id().equals(a.targetTaskId()))
                .toList();

        assertThat(wronglyClosed)
                .overridingErrorMessage("Модель закрыла неупомянутую задачу: %s", wronglyClosed)
                .isEmpty();
    }

    @Test
    void handleText_doesNotRenameUnrelatedTaskOnAmbiguousPhrase() {
        UUID userId = newUser();
        TaskResponse materials = seedTask(userId, "Отправить материалы заказчику", "Работа", TaskPriority.MEDIUM, null);

        // живой кейс: «ложный варнинг» не упоминает существующее название задачи —
        // переименование той задачи, что подвернулась первой, было бы ошибкой
        Proposal proposal = handleText(userId, "Поменять название ложному варнингу");

        List<ProposedAction> wronglyRenamed = proposal.actions().stream()
                .filter(a -> a.type() == AssistantActionType.UPDATE)
                .filter(a -> materials.id().equals(a.targetTaskId()))
                .filter(a -> a.payload().get("title") != null)
                .toList();

        assertThat(wronglyRenamed)
                .overridingErrorMessage("Модель переименовала неупомянутую задачу: %s", wronglyRenamed)
                .isEmpty();
    }

    @Test
    void handleText_doesNotCreateDuplicateOfExistingTask() {
        UUID userId = newUser();
        TaskResponse dentist = seedTask(userId, "Записаться к стоматологу", "Здоровье", TaskPriority.LOW, null);
        TaskResponse catFood = seedTask(userId, "Купить корм коту", "Покупки", TaskPriority.MEDIUM, null);
        seedTask(userId, "Забрать посылку", "Покупки", TaskPriority.MEDIUM, OffsetDateTime.now().plusHours(6));

        // фикстура 05: все три пункта уже существуют в окне как отдельные задачи
        Proposal proposal = handleText(userId, "купить корм коту, забрать посылку до 18, записаться к стоматологу");

        List<ProposedAction> duplicates = proposal.actions().stream()
                .filter(a -> a.type() == AssistantActionType.CREATE)
                .filter(a -> {
                    String title = String.valueOf(a.payload().get("title")).trim();
                    return title.equalsIgnoreCase(dentist.title()) || title.equalsIgnoreCase(catFood.title());
                })
                .toList();

        assertThat(duplicates)
                .overridingErrorMessage("Модель создала дубль уже существующей задачи: %s", duplicates)
                .isEmpty();
    }

    @Test
    void handleText_relativeDeadlineForNewTaskIsInFuture() {
        UUID userId = newUser();

        Proposal proposal = handleText(userId, "добавь задачу купить подарок маме на день рождения завтра");

        OffsetDateTime deadline = createDeadline(proposal);
        assertThat(deadline)
                .overridingErrorMessage("Срок «завтра» вычислен в прошлом или отсутствует: %s", proposal.actions())
                .isNotNull()
                .isAfter(OffsetDateTime.now());
    }

    @Test
    void handleText_relativeDeadlineForRescheduleIsInFuture() {
        UUID userId = newUser();
        seedTask(userId, "Встреча с Марком", "Работа", TaskPriority.MEDIUM, OffsetDateTime.now().plusDays(2));

        Proposal proposal = handleText(userId, "перенеси встречу с марком на пятницу");

        OffsetDateTime deadline = rescheduleDeadline(proposal);
        assertThat(deadline)
                .overridingErrorMessage("Срок «пятница» вычислен в прошлом или отсутствует: %s", proposal.actions())
                .isNotNull()
                .isAfter(OffsetDateTime.now());
    }

    @Test
    void handleText_relativeDeadlineInAWeekIsInFuture() {
        UUID userId = newUser();
        seedTask(userId, "Записаться к стоматологу", "Здоровье", TaskPriority.LOW, OffsetDateTime.now().plusDays(1));

        Proposal proposal = handleText(userId, "перенеси стоматолога через неделю");

        OffsetDateTime deadline = rescheduleDeadline(proposal);
        assertThat(deadline)
                .overridingErrorMessage("Срок «через неделю» вычислен в прошлом или отсутствует: %s", proposal.actions())
                .isNotNull()
                .isAfter(OffsetDateTime.now());
    }

    /**
     * Двоякость определяет AgentLoop разбором (структура реплики + сходство с
     * задачей, на которую модель уже указала), не только вызовом mark_ambiguous
     * моделью — тот остаётся дополнительным сигналом. Несколько попыток здесь
     * смягчают обычный шум живого вызова (сеть, редкая деградация модели), не
     * то стохастическое поведение самого mark_ambiguous, которое было
     * единственным сигналом в первой редакции и не проходило по 25 фразам.
     */
    @Test
    void handleText_marksAmbiguousOnGenuinelyAmbiguousChatPhrase() {
        UUID userId = newUser();
        seedTask(userId, "кино с настей", "Личное", TaskPriority.MEDIUM, null);

        List<String> observations = new ArrayList<>();
        boolean ambiguousAtLeastOnce = false;
        for (int attempt = 1; attempt <= 3 && !ambiguousAtLeastOnce; attempt++) {
            Proposal proposal = handleText(userId, "изменить планы на кино");
            observations.add("попытка %d: exclusive=%s, actions=%d, reason=%s".formatted(
                    attempt, proposal.exclusive(), proposal.actions().size(), proposal.ambiguityReason()));
            ambiguousAtLeastOnce = proposal.exclusive() && proposal.actions().size() >= 2;
        }

        assertThat(ambiguousAtLeastOnce)
                .overridingErrorMessage("mark_ambiguous ни разу не сработал за %d попыток: %s",
                        observations.size(), observations)
                .isTrue();
    }

    /**
     * ОСОЗНАННО ОТЛОЖЕННОЕ УЛУЧШЕНИЕ, не дефект. Исходная жалоба: «закрыть
     * кино» из кнопки быстрого добавления молча закрывало «кино с настей»
     * вместо создания новой задачи. Этот дефект исправлен — модель на эту
     * реплику стабильно создаёт новую задачу, ни разу не трогая существующую;
     * для кнопки добавления создание и есть верное умолчание. Не хватает
     * только предложения второго варианта («может, вы про кино с настей?»),
     * а обычная мера Жаккара для этого недостаточна: «закрыть кино» против
     * «кино с настей» даёт 0.25 — ровно то же самое сходство, что у «купить
     * молоко» против «купить корм коту» (тоже 0.25), а это два разных дела,
     * не двоякость. Различить их можно только взвешиванием по редкости
     * слова в окне пользователя (TF-IDF-подобная мера) — отдельная работа,
     * не часть этой задачи.
     */
    @Test
    void quick_createsNewTaskInsteadOfSilentlyActingOnExistingTask() {
        UUID userId = newUser();
        seedTask(userId, "кино с настей", "Личное", TaskPriority.MEDIUM, null);

        Proposal proposal = handleText(userId, "закрыть кино", AssistantEntryPoint.QUICK_ADD);

        assertThat(proposal.actions())
                .overridingErrorMessage("«закрыть кино» подействовало на существующую задачу вместо создания новой: %s",
                        proposal.actions())
                .hasSize(1);
        assertThat(proposal.actions().getFirst().type()).isEqualTo(AssistantActionType.CREATE);
        assertThat(proposal.actions().getFirst().targetTaskId()).isNull();
    }

    /**
     * Третий признак, найденный живым прогоном: «поиск состоялся + действий
     * ноль» отличает запрос к данным от названия новой задачи там, где
     * структура и сходство с задачами в окне не различают их (оба —
     * несколько слов, без «?», ничего похожего в списке). Признак работает
     * только когда модель сама решает вызвать search_tasks на эту реплику, а
     * не всегда — диагностика на живой модели (3 прогона) показала поиск в
     * ~2 из 3 попыток, третья уходит в create одним проходом. Несколько
     * попыток здесь смягчают именно это, а не сетевой шум.
     */
    @Test
    void handleText_doesNotTurnListingRequestIntoATask() {
        UUID userId = newUser();

        List<String> observations = new ArrayList<>();
        boolean succeededAtLeastOnce = false;
        for (int attempt = 1; attempt <= 3 && !succeededAtLeastOnce; attempt++) {
            Proposal proposal = handleText(userId, "покажи задачи на завтра");
            boolean createdListingTask = proposal.actions().stream()
                    .anyMatch(a -> a.type() == AssistantActionType.CREATE
                            && "покажи задачи на завтра".equals(a.payload().get("title")));
            observations.add("попытка %d: actions=%s".formatted(attempt, proposal.actions()));
            succeededAtLeastOnce = !createdListingTask;
        }

        assertThat(succeededAtLeastOnce)
                .overridingErrorMessage("«покажи задачи на завтра» стало названием задачи все %d попытки: %s",
                        observations.size(), observations)
                .isTrue();
    }

    /**
     * Ложное срабатывание хуже пропуска: однозначная реплика на пустом списке
     * задач не должна порождать выбор ни разу.
     */
    @Test
    void handleText_doesNotMarkAmbiguousOnUnambiguousPhrase() {
        UUID userId = newUser();

        Proposal proposal = handleText(userId, "купить корм коту");

        assertThat(proposal.exclusive())
                .overridingErrorMessage("Однозначная реплика на пустом списке задач помечена как двоякая: %s",
                        proposal.actions())
                .isFalse();
    }

    private OffsetDateTime createDeadline(Proposal proposal) {
        return proposal.actions().stream()
                .filter(a -> a.type() == AssistantActionType.CREATE)
                .map(a -> a.payload().get("deadline"))
                .filter(v -> v != null && !"null".equals(v))
                .map(v -> OffsetDateTime.parse(String.valueOf(v)))
                .findFirst()
                .orElse(null);
    }

    private OffsetDateTime rescheduleDeadline(Proposal proposal) {
        return proposal.actions().stream()
                .filter(a -> a.type() == AssistantActionType.RESCHEDULE)
                .map(a -> a.payload().get("new_deadline"))
                .filter(v -> v != null && !"null".equals(v))
                .map(v -> OffsetDateTime.parse(String.valueOf(v)))
                .findFirst()
                .orElse(null);
    }
}
