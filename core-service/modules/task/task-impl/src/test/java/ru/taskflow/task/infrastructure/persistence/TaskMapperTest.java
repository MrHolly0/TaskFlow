package ru.taskflow.task.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMapperTest {

    private final TaskMapper mapper = Mappers.getMapper(TaskMapper.class);

    @Test
    void toResponse_preservesDraftFlag() {
        var entity = new TaskJpaEntity();
        entity.setTitle("черновик");
        entity.setDraft(true);

        var response = mapper.toResponse(entity);

        assertThat(response.isDraft()).isTrue();
    }

    @Test
    void toResponse_preservesNonDraftFlag() {
        var entity = new TaskJpaEntity();
        entity.setTitle("обычная");
        entity.setDraft(false);

        var response = mapper.toResponse(entity);

        assertThat(response.isDraft()).isFalse();
    }
}
