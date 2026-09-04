package ru.taskflow.task.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TitleHoursGateMatcherTest {

    // Корень для поликлиники должен отличать её от "полить"/"поливать" —
    // ловушка, найденная при прототипировании гибридной меры (§4.3/§4.1).
    private static final List<String> ROOTS = List.of("полик", "банк", "магаз");

    @Test
    void tiedToHours_matchesConfiguredRoot() {
        assertThat(TitleHoursGateMatcher.tiedToHours("записаться в поликлинику", ROOTS)).isTrue();
    }

    @Test
    void tiedToHours_matchesInflectedForm() {
        assertThat(TitleHoursGateMatcher.tiedToHours("сходить в поликлинику", ROOTS)).isTrue();
        assertThat(TitleHoursGateMatcher.tiedToHours("узнать результат в поликлинике", ROOTS)).isTrue();
    }

    @Test
    void tiedToHours_doesNotConflateWithUnrelatedWordSharingPrefix() {
        // "полить цветы" не должно склеиваться с корнем "полик" — ровно та
        // ловушка, что была найдена при прототипировании: "поли" склеил бы их,
        // "полик" — нет.
        assertThat(TitleHoursGateMatcher.tiedToHours("полить цветы", ROOTS)).isFalse();
        assertThat(TitleHoursGateMatcher.tiedToHours("не забыть полить рассаду", ROOTS)).isFalse();
    }

    @Test
    void tiedToHours_isCaseInsensitive() {
        assertThat(TitleHoursGateMatcher.tiedToHours("ПОЗВОНИТЬ В БАНК", ROOTS)).isTrue();
    }

    @Test
    void tiedToHours_ignoresPunctuation() {
        assertThat(TitleHoursGateMatcher.tiedToHours("Банк: перевыпустить карту!", ROOTS)).isTrue();
    }

    @Test
    void tiedToHours_falseForUnrelatedTitle() {
        assertThat(TitleHoursGateMatcher.tiedToHours("написать другу", ROOTS)).isFalse();
    }

    @Test
    void tiedToHours_falseForNullOrBlankTitle() {
        assertThat(TitleHoursGateMatcher.tiedToHours(null, ROOTS)).isFalse();
        assertThat(TitleHoursGateMatcher.tiedToHours("   ", ROOTS)).isFalse();
    }

    @Test
    void tiedToHours_falseWhenNoRootsConfigured() {
        assertThat(TitleHoursGateMatcher.tiedToHours("сходить в банк", List.of())).isFalse();
    }

    @Test
    void tiedToHours_matchesWordAnywhereInTitle() {
        assertThat(TitleHoursGateMatcher.tiedToHours("заехать в магазин по пути домой", ROOTS)).isTrue();
    }
}
