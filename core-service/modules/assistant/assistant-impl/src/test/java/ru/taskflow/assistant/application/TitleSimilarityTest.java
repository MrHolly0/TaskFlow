package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TitleSimilarityTest {

    private final TitleSimilarity similarity = new TitleSimilarity();

    @Test
    void similarity_isOneForExactMatch() {
        assertThat(similarity.similarity("купить корм коту", "купить корм коту")).isEqualTo(1.0);
    }

    @Test
    void similarity_isOneIgnoringCaseAndPunctuation() {
        assertThat(similarity.similarity("Купить корм коту!", "купить корм коту")).isEqualTo(1.0);
    }

    @Test
    void similarity_isZeroForUnrelatedTitles() {
        assertThat(similarity.similarity("купить корм коту", "записаться к стоматологу")).isEqualTo(0.0);
    }

    @Test
    void similarity_forCinemaPhraseAgainstExistingTask() {
        // Тот самый пример из плана Task 5, вторая редакция: пересечение по
        // одному слову «кино» из четырёх уникальных слов — около 0.25.
        double value = similarity.similarity("закрыть кино", "кино с настей");
        assertThat(value).isCloseTo(0.25, within(0.01));
    }

    @Test
    void similarity_toleratesRepeatedWords() {
        // wordsOf строит множество — повтор слова не должен падать с исключением.
        double value = similarity.similarity("сходить сходить в магазин", "сходить в магазин");
        assertThat(value).isGreaterThan(0.0);
    }

    @Test
    void normalize_lowercasesAndStripsPunctuation() {
        assertThat(similarity.normalize("Купить, корм!! коту?")).isEqualTo("купить корм коту");
    }

    @Test
    void wordsOf_splitsOnWhitespace() {
        assertThat(similarity.wordsOf("купить корм коту")).containsExactlyInAnyOrder("купить", "корм", "коту");
    }

    @Test
    void jaccard_isOneForIdenticalSets() {
        var words = similarity.wordsOf("купить корм коту");
        assertThat(similarity.jaccard(words, words)).isEqualTo(1.0);
    }

    @Test
    void jaccard_isZeroForDisjointSets() {
        assertThat(similarity.jaccard(similarity.wordsOf("купить корм"), similarity.wordsOf("записаться врач")))
                .isEqualTo(0.0);
    }
}
