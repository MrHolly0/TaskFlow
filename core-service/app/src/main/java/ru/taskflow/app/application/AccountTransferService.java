package ru.taskflow.app.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.user.api.UserService;

import java.util.UUID;

/**
 * Единственное место, откуда собирается перенос данных между учётками —
 * живёт здесь, а не в одном из модулей, потому что задевает все сразу
 * (tasks, notify, audit, assistant, user identities), а module-impl не может
 * зависеть от другого module-impl. Одна @Transactional-граница на весь метод:
 * все репозитории смотрят в одну и ту же БД через общий EntityManagerFactory
 * (TaskFlowApplication сканирует весь ru.taskflow одним @EnableJpaRepositories),
 * так что либо переносится всё, либо ничего.
 *
 * Идемпотентно по построению: у from после первого успешного переноса не
 * остаётся ни одной строки ни в одной из таблиц, так что повторный вызов
 * просто ничего не находит и возвращает нули — без отдельной проверки «уже
 * переносили».
 */
@Service
@RequiredArgsConstructor
public class AccountTransferService {

    private final TaskService taskService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AssistantService assistantService;
    private final UserService userService;

    @Transactional
    public AccountTransferResult transfer(UUID from, UUID to) {
        // Идентичности переносим первыми: перенос уведомлений пересчитывает
        // адресата каждого канала по текущим идентичностям to, и должен
        // видеть уже перенесённые, а не старые.
        int identities = userService.transferIdentities(from, to);
        var taskResult = taskService.transferOwnership(from, to);
        int notifications = notificationService.transferOwnership(from, to);
        int auditEvents = auditService.transferOwnership(from, to);
        int proposals = assistantService.transferOwnership(from, to);

        return new AccountTransferResult(
                taskResult.tasks(), taskResult.groups(), taskResult.tags(),
                notifications, auditEvents, proposals, identities);
    }
}
