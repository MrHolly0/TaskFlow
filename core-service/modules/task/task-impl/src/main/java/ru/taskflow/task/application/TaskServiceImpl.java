package ru.taskflow.task.application;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.audit.api.AuditEventType;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.shared.exception.ValidationException;
import ru.taskflow.task.api.RecurrenceType;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.TaskSource;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.DigestResponse;
import ru.taskflow.task.api.dto.FocusResponse;
import ru.taskflow.task.api.dto.RecurrenceRule;
import ru.taskflow.task.api.dto.ReminderResponse;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.TaskStatsItem;
import ru.taskflow.task.api.dto.TaskStatsResponse;
import ru.taskflow.task.api.dto.TaskTransferResult;
import ru.taskflow.task.api.dto.UpdateTaskRequest;
import ru.taskflow.task.api.exception.GroupNotFoundException;
import ru.taskflow.task.api.exception.TaskNotFoundException;
import ru.taskflow.task.infrastructure.persistence.*;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Основной сервис управления задачами пользователя.
 *
 * Предоставляет операции для создания, чтения, обновления и удаления задач,
 * а также специализированные эндпоинты для режима фокуса и дайджестов.
 * Поддерживает автоматическую группировку, теги, уведомления и аудит.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskServiceImpl implements TaskService {

    private static final int SEARCH_LIMIT = 20;

    private final TaskRepository taskRepository;
    private final GroupRepository groupRepository;
    private final TagRepository tagRepository;
    private final TaskMapper taskMapper;
    private final TaskReminderService taskReminderService;
    private final ReminderRepository reminderRepository;
    private final RecurrenceRepository recurrenceRepository;
    private final AuditService auditService;
    private final GroupStyleResolver groupStyleResolver;

    /**
     * Создаёт новую задачу для пользователя.
     *
     * Задача сразу видна в списках. Если указана группа,
     * автоматически создаётся, если её не существует.
     * Если указан дедлайн, расписывает напоминание.
     *
     * @param userId ID пользователя
     * @param request параметры новой задачи (название, описание, приоритет и т.д.)
     * @return созданная задача с ID
     */
    @Override
    @Transactional
    public TaskResponse create(UUID userId, CreateTaskRequest request) {
        var task = new TaskJpaEntity();
        task.setUserId(userId);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setPriority(request.priority());
        task.setDeadline(request.deadline());
        task.setEstimateMinutes(request.estimateMinutes());
        task.setSource(request.source());

        task.setGroup(resolveGroup(userId, request.groupId(), request.groupName()));

        if (!request.tags().isEmpty()) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity savedTask = taskRepository.save(task);
        auditService.record(userId, savedTask.getId(), AuditEventType.CREATED, null);

        taskReminderService.planForDeadline(userId, savedTask);

        RecurrenceRule recurrence = request.recurrence() != null
                ? saveRecurrence(savedTask, request.recurrence())
                : null;

        return withRecurrence(taskMapper.toResponse(savedTask), recurrence);
    }

    /**
     * Получает задачу по ID.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @return данные задачи
     * @throws TaskNotFoundException если задача не найдена или принадлежит другому пользователю
     */
    @Override
    public TaskResponse findById(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        var reminders = taskMapper.toReminderResponses(
                reminderRepository.findByTaskIdAndStatusOrderByFireAtAsc(taskId, ReminderStatus.PENDING));
        var recurrence = recurrenceRepository.findById(taskId).map(this::toRule).orElse(null);
        return withRecurrence(withReminders(taskMapper.toResponse(task), reminders), recurrence);
    }

    /**
     * Получает список всех задач пользователя с фильтрацией.
     *
     * Поддерживает фильтрацию по группе, статусу, приоритету и тегам.
     * Результаты постраничные.
     *
     * @param userId ID пользователя
     * @param filter критерии фильтрации
     * @param pageable параметры страницы и сортировки
     * @return страница с задачами
     */
    @Override
    public Page<TaskResponse> findAll(UUID userId, TaskFilterRequest filter, Pageable pageable) {
        Page<TaskJpaEntity> page = taskRepository.findAllWithFilter(
                userId,
                filter.groupId(),
                filter.status(),
                filter.priority(),
                filter.tag(),
                pageable
        );

        // Одна выборка напоминаний на всю страницу, не запрос на каждую
        // карточку (А3) — group by taskId вместо N обращений к reminderRepository.
        List<UUID> taskIds = page.getContent().stream().map(TaskJpaEntity::getId).toList();
        Map<UUID, List<ReminderResponse>> remindersByTaskId = taskIds.isEmpty()
                ? Map.of()
                : reminderRepository.findByTaskIdInAndStatusOrderByFireAtAsc(taskIds, ReminderStatus.PENDING).stream()
                        .collect(Collectors.groupingBy(
                                r -> r.getTask().getId(),
                                Collectors.mapping(taskMapper::toReminderResponse, Collectors.toList())));
        // Тот же приём, что и для напоминаний выше: одна выборка на страницу,
        // не запрос на каждую карточку — task_id сам первичный ключ recurrences,
        // поэтому findAllById достаточно.
        Map<UUID, RecurrenceRule> recurrenceByTaskId = taskIds.isEmpty()
                ? Map.of()
                : recurrenceRepository.findAllById(taskIds).stream()
                        .collect(Collectors.toMap(RecurrenceJpaEntity::getTaskId, this::toRule));

        return page.map(entity -> withRecurrence(
                withReminders(taskMapper.toResponse(entity), remindersByTaskId.getOrDefault(entity.getId(), List.of())),
                recurrenceByTaskId.get(entity.getId())));
    }

    private TaskResponse withReminders(TaskResponse response, List<ReminderResponse> reminders) {
        return new TaskResponse(response.id(), response.title(), response.description(), response.priority(),
                response.status(), response.deadline(), response.plannedDate(), response.estimateMinutes(),
                response.source(), response.groupId(), response.groupName(), response.tags(), response.createdAt(),
                response.updatedAt(), response.completedAt(), reminders, response.startedAt(),
                response.plannedDateSetAt(), response.recurrence());
    }

    private TaskResponse withRecurrence(TaskResponse response, RecurrenceRule recurrence) {
        return new TaskResponse(response.id(), response.title(), response.description(), response.priority(),
                response.status(), response.deadline(), response.plannedDate(), response.estimateMinutes(),
                response.source(), response.groupId(), response.groupName(), response.tags(), response.createdAt(),
                response.updatedAt(), response.completedAt(), response.reminders(), response.startedAt(),
                response.plannedDateSetAt(), recurrence);
    }

    private static final int MAX_DAY_OF_MONTH = 31;

    private void validateRecurrence(RecurrenceRule rule) {
        if (rule.type() == null) {
            throw new ValidationException("не указан тип повтора");
        }
        if (rule.type() == RecurrenceType.CUSTOM) {
            throw new ValidationException("тип повтора CUSTOM пока не поддерживается");
        }
        if (rule.type() == RecurrenceType.MONTHLY
                && (rule.dayOfMonth() == null || rule.dayOfMonth() < 1 || rule.dayOfMonth() > MAX_DAY_OF_MONTH)) {
            throw new ValidationException("для месячного повтора нужен день месяца от 1 до 31");
        }
        if (rule.intervalN() != null && rule.intervalN() < 1) {
            throw new ValidationException("интервал повтора должен быть не меньше 1");
        }
    }

    private RecurrenceRule saveRecurrence(TaskJpaEntity task, RecurrenceRule rule) {
        validateRecurrence(rule);
        RecurrenceJpaEntity entity = recurrenceRepository.findById(task.getId()).orElseGet(RecurrenceJpaEntity::new);
        entity.setTask(task);
        entity.setType(rule.type());
        entity.setIntervalN(rule.intervalN() != null ? rule.intervalN() : 1);
        entity.setDaysOfWeek(encodeDaysOfWeek(rule.daysOfWeek()));
        entity.setDayOfMonth(rule.dayOfMonth());
        entity.setEndsAt(rule.endsAt());
        RecurrenceJpaEntity saved = recurrenceRepository.save(entity);
        return toRule(saved);
    }

    private RecurrenceRule toRule(RecurrenceJpaEntity entity) {
        return new RecurrenceRule(entity.getType(), entity.getIntervalN(), decodeDaysOfWeek(entity.getDaysOfWeek()),
                entity.getDayOfMonth(), entity.getEndsAt());
    }

    private String encodeDaysOfWeek(List<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        return days.stream().map(d -> String.valueOf(d.getValue())).collect(Collectors.joining(","));
    }

    private List<DayOfWeek> decodeDaysOfWeek(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return Arrays.stream(raw.split(",")).map(s -> DayOfWeek.of(Integer.parseInt(s.trim()))).toList();
    }

    /**
     * Обновляет параметры задачи.
     *
     * Если изменился дедлайн или название, отправляет обновление в сервис уведомлений.
     * Если статус переходит в DONE или CANCELLED, запланированные напоминания отменяются
     * без переназначения. Записывает событие в аудит.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @param request новые параметры
     * @return обновленная задача
     * @throws TaskNotFoundException если задача не найдена
     */
    @Override
    @Transactional
    public TaskResponse update(UUID userId, UUID taskId, UpdateTaskRequest request) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        // Считаем дельту до мутации task — buildDelta сравнивает request с текущим
        // состоянием, а не "было/стало"; после сеттеров ниже task.getX() уже равен
        // request.X(), и сравнение всегда было бы истинным.
        Map<String, Object> delta = buildDelta(task, request);
        UUID oldGroupId = task.getGroup() != null ? task.getGroup().getId() : null;

        boolean deadlineChanged = request.deadline() != null;
        boolean titleChanged = request.title() != null;

        if (request.title() != null) task.setTitle(request.title());
        if (request.description() != null) task.setDescription(request.description());
        if (request.priority() != null) task.setPriority(request.priority());
        if (request.deadline() != null) task.setDeadline(request.deadline());
        if (request.estimateMinutes() != null) task.setEstimateMinutes(request.estimateMinutes());
        // Отдельный путь: день исполнения не участвует в deadlineChanged ниже
        // и не трогает напоминания — они привязаны к deadline, а не к нему.
        if (request.plannedDate() != null) {
            task.setPlannedDate(request.plannedDate());
            task.setPlannedDateSetAt(OffsetDateTime.now());
        }

        if (request.status() != null) {
            task.setStatus(request.status());
            if (request.status() == TaskStatus.DONE && task.getCompletedAt() == null) {
                task.setCompletedAt(OffsetDateTime.now());
            }
            if (request.status() == TaskStatus.IN_PROGRESS) {
                task.setStartedAt(OffsetDateTime.now());
            }
        }

        boolean groupRequested = request.groupId() != null
                || (request.groupName() != null && !request.groupName().isBlank());
        if (groupRequested) {
            task.setGroup(resolveGroup(userId, request.groupId(), request.groupName()));
        }

        if (request.tags() != null) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity updatedTask = taskRepository.save(task);

        if (groupRequested) {
            UUID newGroupId = updatedTask.getGroup() != null ? updatedTask.getGroup().getId() : null;
            if (!Objects.equals(oldGroupId, newGroupId)) {
                delta.put("groupId", newGroupId);
            }
        }
        auditService.record(userId, taskId, AuditEventType.UPDATED, delta.isEmpty() ? null : delta);

        boolean becameTerminal = request.status() == TaskStatus.DONE || request.status() == TaskStatus.CANCELLED;
        if (becameTerminal) {
            taskReminderService.cancelForTask(taskId);
        } else if (deadlineChanged || titleChanged) {
            taskReminderService.cancelForTask(taskId);
            taskReminderService.planForDeadline(userId, updatedTask);
        }

        RecurrenceRule recurrence = request.recurrence() != null
                ? saveRecurrence(updatedTask, request.recurrence())
                : recurrenceRepository.findById(taskId).map(this::toRule).orElse(null);

        return withRecurrence(taskMapper.toResponse(updatedTask), recurrence);
    }

    /**
     * Отмечает задачу как выполненную.
     *
     * Устанавливает статус DONE, фиксирует время завершения,
     * отменяет запланированные напоминания и, если у задачи есть правило
     * повтора, порождает следующее вхождение (А2). Отмена (CANCELLED,
     * см. update()) вхождение намеренно не порождает — этот путь через неё
     * не проходит.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @throws TaskNotFoundException если задача не найдена
     */
    @Override
    @Transactional
    public void complete(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        task.setStatus(TaskStatus.DONE);
        task.setCompletedAt(OffsetDateTime.now());
        taskRepository.save(task);
        taskReminderService.cancelForTask(taskId);
        auditService.record(userId, taskId, AuditEventType.STATUS_CHANGED, Map.of("status", "DONE"));
    }

    /**
     * Удаляет задачу (мягкое удаление).
     *
     * Задача помечается как удалённая с фиксацией времени,
     * запланированные напоминания отменяются.
     *
     * @param userId ID пользователя
     * @param taskId ID задачи
     * @throws TaskNotFoundException если задача не найдена
     */
    @Override
    @Transactional
    public void delete(UUID userId, UUID taskId) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        task.setDeleted(true);
        task.setDeletedAt(OffsetDateTime.now());
        taskRepository.save(task);
        taskReminderService.cancelForTask(taskId);
        auditService.record(userId, taskId, AuditEventType.DELETED, null);
    }

    private List<TagJpaEntity> resolveOrCreateTags(UUID userId, List<String> tagNames) {
        var existing = tagRepository.findAllByUserIdAndNameIn(userId, tagNames);
        var existingNames = existing.stream().map(TagJpaEntity::getName).toList();

        List<TagJpaEntity> result = new ArrayList<>(existing);
        for (String name : tagNames) {
            if (!existingNames.contains(name)) {
                var tag = new TagJpaEntity();
                tag.setUserId(userId);
                tag.setName(name);
                result.add(tagRepository.save(tag));
            }
        }
        return result;
    }

    private GroupJpaEntity resolveGroup(UUID userId, UUID groupId, String groupName) {
        if (groupId != null) {
            return groupRepository.findByIdAndUserId(groupId, userId)
                    .orElseThrow(() -> new GroupNotFoundException(groupId));
        }
        if (groupName != null && !groupName.isBlank()) {
            return groupRepository.findByUserIdAndName(userId, groupName)
                    .orElseGet(() -> {
                        var g = new GroupJpaEntity();
                        g.setUserId(userId);
                        g.setName(groupName);
                        var style = groupStyleResolver.resolve(groupName);
                        g.setColor(style.color());
                        g.setIcon(style.icon());
                        return groupRepository.save(g);
                    });
        }
        return null;
    }

    private Map<String, Object> buildDelta(TaskJpaEntity task, UpdateTaskRequest request) {
        Map<String, Object> delta = new HashMap<>();
        if (request.title() != null && !request.title().equals(task.getTitle())) {
            delta.put("title", request.title());
        }
        if (request.description() != null && !request.description().equals(task.getDescription())) {
            delta.put("description", request.description());
        }
        if (request.priority() != null && !request.priority().equals(task.getPriority())) {
            delta.put("priority", request.priority());
        }
        if (request.deadline() != null && !request.deadline().equals(task.getDeadline())) {
            delta.put("deadline", request.deadline());
        }
        if (request.estimateMinutes() != null && !request.estimateMinutes().equals(task.getEstimateMinutes())) {
            delta.put("estimateMinutes", request.estimateMinutes());
        }
        if (request.plannedDate() != null && !request.plannedDate().equals(task.getPlannedDate())) {
            delta.put("plannedDate", request.plannedDate());
        }
        if (request.recurrence() != null) {
            delta.put("recurrence", request.recurrence().type().name());
        }
        if (request.status() != null && !request.status().equals(task.getStatus())) {
            delta.put("status", request.status());
        }
        return delta;
    }

    /**
     * Получает ограниченный список задач для режима фокуса.
     *
     * Возвращает до 3 актуальных задач на сегодня (с дедлайном до конца дня),
     * исключая выполненные.
     *
     * @param userId ID пользователя
     * @return ответ с задачами для фокуса
     */
    @Override
    public FocusResponse getFocusTasks(UUID userId, Integer availableMinutes) {
        var endOfToday = endOfToday();
        var tasks = taskRepository.findFocusTasks(userId, TaskStatus.DONE, endOfToday)
                .stream()
                .filter(t -> fitsAvailableTime(t, availableMinutes))
                .limit(3)
                .map(taskMapper::toResponse)
                .toList();
        return new FocusResponse(tasks);
    }

    /**
     * План на сегодня закрыт — не то же самое, что задач больше нет. Отдельный
     * вызов вместо расширения getFocusTasks: экран сначала честно показывает
     * «сегодня всё сделано», и только по запросу подтягивает то, что дальше.
     */
    @Override
    public FocusResponse getUpcomingFocusTasks(UUID userId, Integer availableMinutes) {
        var endOfToday = endOfToday();
        var tasks = taskRepository.findUpcomingFocusTasks(userId, TaskStatus.DONE, endOfToday)
                .stream()
                .filter(t -> fitsAvailableTime(t, availableMinutes))
                .limit(3)
                .map(taskMapper::toResponse)
                .toList();
        return new FocusResponse(tasks);
    }

    // Отбор до limit(3), не после: иначе время могло бы отфильтровать
    // подходящую задачу, которую уже отрезал .limit по неподходящим впереди неё.
    // Без оценки длительности задача участвует всегда — у большинства задач её нет.
    private boolean fitsAvailableTime(TaskJpaEntity task, Integer availableMinutes) {
        return availableMinutes == null
                || task.getEstimateMinutes() == null
                || task.getEstimateMinutes() <= availableMinutes;
    }

    private OffsetDateTime endOfToday() {
        return OffsetDateTime.now(ZoneOffset.UTC)
                .withHour(23).withMinute(59).withSecond(59).withNano(0);
    }

    /**
     * Получает дайджест по задачам на конкретную дату.
     *
     * Агрегирует статистику по выполненным, просроченным и активным задачам.
     *
     * @param userId ID пользователя
     * @param date дата для дайджеста
     * @return ответ с статистикой и списком задач
     */
    @Override
    public DigestResponse getDigest(UUID userId, LocalDate date) {
        var startOfDay = date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        var allTasks = taskRepository.findDigestTasks(userId, startOfDay, TaskStatus.DONE);

        var topTasks = allTasks.stream()
                .limit(5)
                .map(taskMapper::toResponse)
                .toList();

        long totalTasks = allTasks.size();
        long completedToday = allTasks.stream()
                .filter(t -> t.getStatus() == TaskStatus.DONE)
                .count();

        long overdueTasks = allTasks.stream()
                .filter(t -> t.getDeadline() != null && t.getDeadline().isBefore(OffsetDateTime.now()) && t.getStatus() != TaskStatus.DONE)
                .count();

        return new DigestResponse(topTasks, totalTasks, completedToday, overdueTasks);
    }

    @Override
    public TaskStatsResponse getStats(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime from = now.minusDays(8);
        List<TaskStatsItem> tasks = taskRepository.findStatsRows(userId, from, now).stream()
                .map(this::toStatsItem)
                .toList();
        return new TaskStatsResponse(tasks);
    }

    @Override
    @Transactional
    public TaskTransferResult transferOwnership(UUID from, UUID to) {
        int tasks = taskRepository.reassignOwner(from, to);
        int groups = groupRepository.reassignOwner(from, to);
        int tags = tagRepository.reassignOwner(from, to);
        return new TaskTransferResult(tasks, groups, tags);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskTransferResult countOwnership(UUID userId) {
        long tasks = taskRepository.countByUserId(userId);
        long groups = groupRepository.countByUserId(userId);
        long tags = tagRepository.countByUserId(userId);
        return new TaskTransferResult((int) tasks, (int) groups, (int) tags);
    }

    private TaskStatsItem toStatsItem(Object[] row) {
        return new TaskStatsItem(
                toOffsetDateTime(row[0]),
                toOffsetDateTime(row[1]),
                toOffsetDateTime(row[2]),
                TaskStatus.valueOf(row[3].toString())
        );
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atOffset(ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(value.toString());
    }

    /**
     * Создаёт задачу в статусе активной (не draft) с быстрым способом.
     *
     * Используется для быстрого добавления задач через боты и интеграции.
     * Автоматически расписывает напоминания если указан дедлайн.
     *
     * @param userId ID пользователя
     * @param request параметры новой задачи
     * @return созданная активная задача
     */
    @Override
    @Transactional
    public TaskResponse createQuick(UUID userId, CreateTaskRequest request) {
        var task = new TaskJpaEntity();
        task.setUserId(userId);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task.setPriority(request.priority());
        task.setDeadline(request.deadline());
        task.setEstimateMinutes(request.estimateMinutes());
        task.setSource(request.source());
        task.setStatus(TaskStatus.TODO);

        task.setGroup(resolveGroup(userId, request.groupId(), request.groupName()));

        if (!request.tags().isEmpty()) {
            task.setTags(resolveOrCreateTags(userId, request.tags()));
        }

        TaskJpaEntity savedTask = taskRepository.save(task);

        taskReminderService.planForDeadline(userId, savedTask);

        RecurrenceRule recurrence = request.recurrence() != null
                ? saveRecurrence(savedTask, request.recurrence())
                : null;

        return withRecurrence(taskMapper.toResponse(savedTask), recurrence);
    }

    @Override
    @Transactional
    public void scheduleReminder(UUID userId, UUID taskId, OffsetDateTime fireAt) {
        var task = taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        taskReminderService.createStandaloneReminder(userId, task, fireAt);
    }

    @Override
    public List<ReminderResponse> getReminders(UUID userId, UUID taskId) {
        taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        return taskMapper.toReminderResponses(
                reminderRepository.findByTaskIdAndStatusOrderByFireAtAsc(taskId, ReminderStatus.PENDING));
    }

    @Override
    @Transactional
    public void cancelReminder(UUID userId, UUID taskId, UUID reminderId) {
        taskRepository.findByIdAndUserId(taskId, userId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        taskReminderService.cancelReminder(taskId, reminderId);
    }

    @Override
    public List<String> findGroupNames(UUID userId) {
        return groupRepository.findAllByUserId(userId).stream()
            .map(GroupJpaEntity::getName)
            .toList();
    }

    @Override
    @Transactional
    public int clearCompleted(UUID userId) {
        return taskRepository.softDeleteAllCompletedByUser(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> findAssistantContext(UUID userId, int limit) {
        return taskRepository
                .findAssistantContext(userId, OffsetDateTime.now(), PageRequest.of(0, limit))
                .stream()
                .map(taskMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> search(UUID userId, String query, boolean includeCompleted, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int capped = Math.min(limit <= 0 ? SEARCH_LIMIT : limit, SEARCH_LIMIT);
        return taskRepository.search(userId, query.trim(), includeCompleted, PageRequest.of(0, capped))
                .stream()
                .map(taskMapper::toResponse)
                .toList();
    }
}
