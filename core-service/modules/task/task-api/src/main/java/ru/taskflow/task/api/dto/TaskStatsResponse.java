package ru.taskflow.task.api.dto;

import java.util.List;

public record TaskStatsResponse(
        List<TaskStatsItem> tasks
) {}
