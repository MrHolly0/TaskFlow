package ru.taskflow.app.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.taskflow.task.infrastructure.persistence.TaskRepository;
import ru.taskflow.user.infrastructure.persistence.UserSettingsRepository;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaskCleanupScheduler {

    private static final int SYSTEM_PURGE_DAYS = 90;

    private final TaskRepository taskRepository;
    private final UserSettingsRepository settingsRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void autoHideCompleted() {
        var settings = settingsRepository.findAllByAutoCleanCompletedDaysNotNull();
        for (var s : settings) {
            var cutoff = OffsetDateTime.now().minusDays(s.getAutoCleanCompletedDays());
            int count = taskRepository.softDeleteCompletedBefore(s.getUserId(), cutoff);
            if (count > 0) {
                log.info("auto-hidden {} completed tasks for user {}", count, s.getUserId());
            }
        }
    }

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void systemPurge() {
        var cutoff = OffsetDateTime.now().minusDays(SYSTEM_PURGE_DAYS);
        int count = taskRepository.physicalDeleteCompletedBefore(cutoff);
        log.info("system purge: deleted {} completed tasks older than {} days", count, SYSTEM_PURGE_DAYS);
    }
}
