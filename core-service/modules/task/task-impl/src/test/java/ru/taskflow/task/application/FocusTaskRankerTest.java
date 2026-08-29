package ru.taskflow.task.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.infrastructure.persistence.TaskJpaEntity;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FocusTaskRankerTest {

    private final FocusTaskRanker ranker = new FocusTaskRanker();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-08-29T10:00:00Z");

    private TaskJpaEntity dateless(String title, OffsetDateTime createdAt, OffsetDateTime lastShownInFocusAt,
                                    TaskPriority priority) {
        var t = new TaskJpaEntity();
        t.setTitle(title);
        t.setCreatedAt(createdAt);
        t.setLastShownInFocusAt(lastShownInFocusAt);
        t.setPriority(priority);
        return t;
    }

    private TaskJpaEntity withDeadline(String title, OffsetDateTime deadline) {
        var t = new TaskJpaEntity();
        t.setTitle(title);
        t.setDeadline(deadline);
        t.setCreatedAt(now.minusDays(1));
        return t;
    }

    // Бессрочная задача, показанная давно, поднимается выше показанной вчера (А4).
    @Test
    void staleUndatedTask_ranksAboveRecentlyShownUndatedTask() {
        var shownYesterday = dateless("вчера показанная", now.minusDays(60), now.minusDays(1), TaskPriority.MEDIUM);
        var shownLongAgo = dateless("давно показанная", now.minusDays(60), now.minusDays(45), TaskPriority.MEDIUM);

        var ranked = ranker.rank(List.of(shownYesterday, shownLongAgo), now);

        assertThat(ranked).containsExactly(shownLongAgo, shownYesterday);
    }

    // Задача со сроком на сегодня остаётся впереди любой бессрочной (А4) —
    // даже перед бессрочной, которую не показывали никогда.
    @Test
    void taskDueToday_staysAheadOfAnyUndatedTask_evenNeverShownOne() {
        var dueToday = withDeadline("срок сегодня", now.minusHours(1));
        var neverShownUndated = dateless("никогда не показанная", now.minusDays(400), null, TaskPriority.URGENT);

        var ranked = ranker.rank(List.of(neverShownUndated, dueToday), now);

        assertThat(ranked).containsExactly(dueToday, neverShownUndated);
    }

    // Ранкер не назначает ни deadline, ни plannedDate — только меняет порядок.
    @Test
    void ranking_neverAssignsDeadlineOrPlannedDate() {
        var task = dateless("бессрочная", now.minusDays(90), null, TaskPriority.LOW);

        ranker.rank(List.of(task), now);

        assertThat(task.getDeadline()).isNull();
        assertThat(task.getPlannedDate()).isNull();
    }

    // Приоритет решает в ближней перспективе, но не бессрочно — сильно
    // залежавшаяся задача обычного приоритета обгоняет только что показанную
    // срочную (обе бессрочные, без deadline и без plannedDate).
    @Test
    void veryStaleTask_outranksRecentlyShownHigherPriorityTask() {
        var recentUrgent = dateless("срочная, но показана только что", now.minusDays(60), now, TaskPriority.URGENT);
        var staleMedium = dateless("обычная, но месяц без показа", now.minusDays(60), now.minusDays(30), TaskPriority.MEDIUM);

        var ranked = ranker.rank(List.of(recentUrgent, staleMedium), now);

        assertThat(ranked).containsExactly(staleMedium, recentUrgent);
    }

    // Задача, у которой есть только plannedDate (день настал, deadline нет),
    // не участвует в очковой давности — сравнивается как раньше, по приоритету.
    @Test
    void plannedDateDueTask_doesNotUseStalenessScoring_comparedByPriority() {
        var plannedDue = new TaskJpaEntity();
        plannedDue.setTitle("день настал");
        plannedDue.setPlannedDate(now.minusHours(2));
        plannedDue.setPriority(TaskPriority.LOW);
        plannedDue.setCreatedAt(now.minusDays(1));

        var undatedUrgentButNeverShown = dateless("бессрочная срочная", now.minusDays(500), null, TaskPriority.URGENT);

        var ranked = ranker.rank(List.of(plannedDue, undatedUrgentButNeverShown), now);

        // plannedDate-задачи — свой ярус, впереди бессрочных безусловно,
        // сколько бы очков ни набрала бессрочная.
        assertThat(ranked).containsExactly(plannedDue, undatedUrgentButNeverShown);
    }

    @Test
    void inProgressTask_ranksAheadOfSamePriorityTaskWithDeadline() {
        var inProgress = withDeadline("в работе", now.plusHours(5));
        inProgress.setStatus(TaskStatus.IN_PROGRESS);
        var todo = withDeadline("к выполнению", now.plusHours(1));

        var ranked = ranker.rank(List.of(todo, inProgress), now);

        assertThat(ranked).containsExactly(inProgress, todo);
    }
}
