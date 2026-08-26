package ru.taskflow.task.infrastructure.persistence;

import org.mapstruct.*;
import ru.taskflow.task.api.dto.ReminderResponse;
import ru.taskflow.task.api.dto.TaskResponse;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TaskMapper {

    @Mapping(target = "groupId", source = "group.id")
    @Mapping(target = "groupName", source = "group.name")
    @Mapping(target = "tags", expression = "java(tagNames(entity))")
    // Задача без напоминаний должна отдавать пустой список, а не null (А4) —
    // здесь это дефолт для всех путей, кроме findById/findAll, которые
    // подставляют настоящий список отдельным запросом (TaskServiceImpl.withReminders).
    @Mapping(target = "reminders", expression = "java(java.util.List.of())")
    TaskResponse toResponse(TaskJpaEntity entity);

    default List<String> tagNames(TaskJpaEntity entity) {
        return entity.getTags().stream().map(TagJpaEntity::getName).toList();
    }

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ReminderResponse toReminderResponse(ReminderJpaEntity entity);

    List<ReminderResponse> toReminderResponses(List<ReminderJpaEntity> entities);
}
