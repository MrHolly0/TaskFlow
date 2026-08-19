package ru.taskflow.app.web.dto;

/**
 * Тело 409-ответа на bind-запрос, когда идентификатор уже принадлежит
 * другой учётке. Числа — предпросмотр состава чужой учётки для диалога
 * согласия, mergeToken — одноразовый пропуск в POST /identities/merge
 * на 10 минут.
 */
public record MergeConflictResponse(
        int tasks,
        int groups,
        int tags,
        String mergeToken
) {
}
