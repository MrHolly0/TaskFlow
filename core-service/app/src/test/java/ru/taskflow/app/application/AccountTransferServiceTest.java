package ru.taskflow.app.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.audit.api.AuditService;
import ru.taskflow.notify.api.NotificationService;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.task.api.dto.TaskTransferResult;
import ru.taskflow.user.api.UserService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountTransferServiceTest {

    @Mock
    private TaskService taskService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private AuditService auditService;
    @Mock
    private AssistantService assistantService;
    @Mock
    private UserService userService;

    @Test
    void transfer_combinesCountsFromEveryModule() {
        var svc = new AccountTransferService(taskService, notificationService, auditService, assistantService, userService);
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(taskService.transferOwnership(from, to)).thenReturn(new TaskTransferResult(120, 5, 3));
        when(notificationService.transferOwnership(from, to)).thenReturn(30);
        when(auditService.transferOwnership(from, to)).thenReturn(120);
        when(assistantService.transferOwnership(from, to)).thenReturn(2);
        when(userService.transferIdentities(from, to)).thenReturn(1);

        var result = svc.transfer(from, to);

        assertThat(result.tasks()).isEqualTo(120);
        assertThat(result.groups()).isEqualTo(5);
        assertThat(result.tags()).isEqualTo(3);
        assertThat(result.notifications()).isEqualTo(30);
        assertThat(result.auditEvents()).isEqualTo(120);
        assertThat(result.proposals()).isEqualTo(2);
        assertThat(result.identities()).isEqualTo(1);
    }

    @Test
    void transfer_movesIdentitiesFirst_soNotificationTransferSeesUpdatedTelegramIdentity() {
        var svc = new AccountTransferService(taskService, notificationService, auditService, assistantService, userService);
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(taskService.transferOwnership(from, to)).thenReturn(new TaskTransferResult(0, 0, 0));

        svc.transfer(from, to);

        InOrder order = inOrder(userService, notificationService);
        order.verify(userService).transferIdentities(from, to);
        order.verify(notificationService).transferOwnership(from, to);
    }
}
