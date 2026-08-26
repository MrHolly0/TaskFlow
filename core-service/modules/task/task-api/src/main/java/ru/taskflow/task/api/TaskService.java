package ru.taskflow.task.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.taskflow.task.api.dto.CreateTaskRequest;
import ru.taskflow.task.api.dto.DigestResponse;
import ru.taskflow.task.api.dto.FocusResponse;
import ru.taskflow.task.api.dto.TaskFilterRequest;
import ru.taskflow.task.api.dto.TaskResponse;
import ru.taskflow.task.api.dto.TaskStatsResponse;
import ru.taskflow.task.api.dto.TaskTransferResult;
import ru.taskflow.task.api.dto.UpdateTaskRequest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TaskService {

    TaskResponse create(UUID userId, CreateTaskRequest request);

    TaskResponse findById(UUID userId, UUID taskId);

    Page<TaskResponse> findAll(UUID userId, TaskFilterRequest filter, Pageable pageable);

    TaskResponse update(UUID userId, UUID taskId, UpdateTaskRequest request);

    void complete(UUID userId, UUID taskId);

    /**
     * Напоминание на конкретное время независимо от срока задачи (Б2) — срок
     * обязательство перед кем-то, напоминание просьба к себе, и задача может
     * не иметь дедлайна вовсе. Не меняет deadline задачи.
     */
    void scheduleReminder(UUID userId, UUID taskId, OffsetDateTime fireAt);

    void delete(UUID userId, UUID taskId);

    FocusResponse getFocusTasks(UUID userId);

    FocusResponse getUpcomingFocusTasks(UUID userId);

    DigestResponse getDigest(UUID userId, LocalDate date);

    TaskStatsResponse getStats(UUID userId);

    TaskResponse createQuick(UUID userId, CreateTaskRequest request);

    List<String> findGroupNames(UUID userId);

    int clearCompleted(UUID userId);

    List<TaskResponse> findAssistantContext(UUID userId, int limit);

    /**
     * Поиск по подстроке в названии и описании. Возвращает не более limit совпадений,
     * жёсткий предел — 20: результат уходит в промпт модели.
     */
    List<TaskResponse> search(UUID userId, String query, boolean includeCompleted, int limit);

    /**
     * Переносит задачи, группы и метки с одной учётки на другую. Массовым
     * UPDATE, без выборки — переносит и мягко удалённые задачи тоже.
     * Идемпотентно: повторный вызов после того, как у from ничего не осталось,
     * просто ничего не находит и возвращает нули.
     */
    TaskTransferResult transferOwnership(UUID from, UUID to);

    /**
     * Сколько задач, групп и меток сейчас у учётки — предпросмотр для
     * диалога согласия на слияние, без единой записи. Мягко удалённые задачи
     * не считаются: это число показывается человеку, а не техническое.
     */
    TaskTransferResult countOwnership(UUID userId);
}
