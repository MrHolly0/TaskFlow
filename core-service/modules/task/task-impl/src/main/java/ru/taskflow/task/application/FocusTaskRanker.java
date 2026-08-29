package ru.taskflow.task.application;

import org.springframework.stereotype.Component;
import ru.taskflow.task.api.TaskPriority;
import ru.taskflow.task.api.TaskStatus;
import ru.taskflow.task.infrastructure.persistence.TaskJpaEntity;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Порядок в фокусе (docs/vkr/исследование-задачи-без-срока.md, §3.5): срочное
 * и просроченное остаётся впереди безусловно, среди бессрочных задач без дня
 * исполнения решает не только приоритет, но и давность показа — иначе
 * задача с обычным приоритетом может не всплыть никогда (исходная проблема
 * блока А).
 * <p>
 * Три яруса, между собой не сравниваются:
 * <ol>
 *   <li>есть deadline — «срочное и просроченное», порядок как раньше
 *   (приоритет, «в работе» вперёд, ближе срок вперёд);</li>
 *   <li>нет deadline, но есть plannedDate (уже наступил день, который
 *   выбрал сам пользователь/ассистент) — тот же порядок, без даты сравнивать
 *   нечего;</li>
 *   <li>нет ни deadline, ни plannedDate — по очкам ниже.</li>
 * </ol>
 */
@Component
public class FocusTaskRanker {

    // Шаг приоритета — не абсолютный барьер: разница в 3 очка перекрывается
    // тремя днями давности показа. Приоритет решает, кто выйдет вперёд
    // сегодня-завтра, но не может держать задачу в тени неделями — иначе
    // возврат снова не гарантирован, а именно это требует §3.5.
    private static final int PRIORITY_STEP = 3;
    // Месяц — верхний ритм пересмотра из GTD (§3.5: «часть пересматривается
    // ежемесячно»): дальше этого разница в днях уже не должна ничего решать.
    private static final int STALENESS_CAP_DAYS = 30;
    // Мягкая добавка для очень старых задач поверх давности показа — без
    // неё задача, которую исправно показывают раз в месяц, никогда не
    // получит небольшой перевес просто за то, что копится полгода без дела.
    private static final int AGE_BONUS_CAP_DAYS = 180;
    private static final int AGE_DIVISOR = 30;

    public List<TaskJpaEntity> rank(List<TaskJpaEntity> candidates, OffsetDateTime now) {
        Comparator<TaskJpaEntity> withinFixedTier = Comparator
                .comparingInt(this::priorityOrder)
                .thenComparing(t -> t.getStatus() != TaskStatus.IN_PROGRESS)
                .thenComparing(TaskJpaEntity::getDeadline, Comparator.nullsLast(Comparator.naturalOrder()));

        // Внутри трёх ярусов сортировка разная (см. javadoc класса), поэтому
        // проще и понятнее разложить кандидатов на три списка и склеить,
        // чем городить один комбинированный компаратор.
        List<TaskJpaEntity> dated = candidates.stream()
                .filter(t -> t.getDeadline() != null)
                .sorted(withinFixedTier)
                .toList();
        List<TaskJpaEntity> plannedDue = candidates.stream()
                .filter(t -> t.getDeadline() == null && t.getPlannedDate() != null)
                .sorted(withinFixedTier)
                .toList();
        List<TaskJpaEntity> dateless = candidates.stream()
                .filter(t -> t.getDeadline() == null && t.getPlannedDate() == null)
                .sorted(Comparator.comparingDouble((TaskJpaEntity t) -> -score(t, now))
                        .thenComparing(TaskJpaEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TaskJpaEntity::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<TaskJpaEntity> result = new ArrayList<>(candidates.size());
        result.addAll(dated);
        result.addAll(plannedDue);
        result.addAll(dateless);
        return result;
    }

    private int priorityOrder(TaskJpaEntity t) {
        return switch (t.getPriority()) {
            case URGENT -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
        };
    }

    private int priorityPoints(TaskPriority priority) {
        return switch (priority) {
            case URGENT -> 3 * PRIORITY_STEP;
            case HIGH -> 2 * PRIORITY_STEP;
            case MEDIUM -> 1 * PRIORITY_STEP;
            case LOW -> 0;
        };
    }

    /**
     * Давность показа — главный фактор: никогда не показанная задача
     * настолько же «давняя», насколько она старая (не бесконечно давняя),
     * иначе только что созданная бессрочная задача обгоняла бы всё сразу.
     */
    private double score(TaskJpaEntity t, OffsetDateTime now) {
        OffsetDateTime referenceForStaleness = t.getLastShownInFocusAt() != null
                ? t.getLastShownInFocusAt()
                : t.getCreatedAt();
        long daysSinceShown = referenceForStaleness == null
                ? 0
                : ChronoUnit.DAYS.between(referenceForStaleness, now);
        long daysOld = t.getCreatedAt() == null ? 0 : ChronoUnit.DAYS.between(t.getCreatedAt(), now);

        double stalenessPoints = Math.min(Math.max(daysSinceShown, 0), STALENESS_CAP_DAYS);
        double agePoints = Math.min(Math.max(daysOld, 0), AGE_BONUS_CAP_DAYS) / (double) AGE_DIVISOR;

        return priorityPoints(t.getPriority()) + stalenessPoints + agePoints;
    }
}
