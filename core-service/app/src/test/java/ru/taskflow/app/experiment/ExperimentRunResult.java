package ru.taskflow.app.experiment;

/**
 * Все показатели одного обращения — заполняется целиком за один проход
 * (Б2 ТЗ), ничего не добирается отдельным прогоном. Плоская запись
 * специально для CSV: вложенные описания (missing/extra/mismatch/rejections)
 * сведены в строки, разделённые " | ".
 */
public record ExperimentRunResult(
        String datasetId,
        String category,
        int attempt,
        String text,

        // разбор
        int expectedActionCount,
        int actualActionCount,
        boolean countCorrect,
        boolean fullyCorrect,
        int missingCount,
        int extraCount,
        int attributeMismatchCount,
        String actualActionTypes,
        String missingDescriptions,
        String extraDescriptions,
        String attributeMismatchDescriptions,
        // "CREATE:2/2;COMPLETE:1/1;RESCHEDULE:0/1" — matched/expected по каждому типу
        // операции, встретившемуся в ожидании этой строки; источник таблицы Б3
        // "операция — верно — ошибок — доля верных" без отдельного файла на пару.
        String perTypeBreakdown,

        // задержки, миллисекунды; -1 значит "не измерено в этом прогоне"
        long totalLatencyMs,
        long firstPassLatencyMs,
        long secondPassLatencyMs,
        long transcriptionLatencyMs,

        // токены
        int inputTokens,
        int outputTokens,

        // вызовы инструментов
        int modelPasses,
        boolean usedSearch,
        int proposedBatchSize,

        // отсев
        int rejectionCount,
        String rejections,

        // двоякость
        boolean expectedAmbiguous,
        boolean choiceOffered,
        boolean modelMarkedAmbiguous,
        boolean deterministicAmbiguityDetected,
        String ambiguityReason,

        // прочее
        String proposalStatus,
        boolean llmFailed,
        String error
) {
    public static final long NOT_MEASURED = -1L;
}
