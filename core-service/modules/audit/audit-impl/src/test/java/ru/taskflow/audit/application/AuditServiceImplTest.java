package ru.taskflow.audit.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.audit.infrastructure.persistence.TaskEventRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock
    private TaskEventRepository taskEventRepository;

    private AuditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditServiceImpl(taskEventRepository, new ObjectMapper());
    }

    @Test
    void transferOwnership_delegatesToRepository() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        when(taskEventRepository.reassignOwner(from, to)).thenReturn(7);

        int result = service.transferOwnership(from, to);

        assertThat(result).isEqualTo(7);
        verify(taskEventRepository).reassignOwner(from, to);
    }
}
