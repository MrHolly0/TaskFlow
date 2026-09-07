package ru.taskflow.task.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TitleHoursGateMatcherTest {

    private static final List<String> ROOTS = List.of(
            "поликлин", "больниц", "аптек", "магаз", "мфц", "нотар", "автосерв",
            "химчист", "загс", "соцзащит", "полиц", "консульст", "ветеринар");

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
        assertThat(TitleHoursGateMatcher.tiedToHours("ПОЗВОНИТЬ В АПТЕКУ", ROOTS)).isTrue();
    }

    @Test
    void tiedToHours_ignoresPunctuation() {
        assertThat(TitleHoursGateMatcher.tiedToHours("Аптека: купить лекарство!", ROOTS)).isTrue();
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
        assertThat(TitleHoursGateMatcher.tiedToHours("сходить в аптеку", List.of())).isFalse();
    }

    @Test
    void tiedToHours_matchesWordAnywhereInTitle() {
        assertThat(TitleHoursGateMatcher.tiedToHours("заехать в автосервис по пути домой", ROOTS)).isTrue();
    }

    @Test
    void tiedToHours_doesNotMatchCheckedPrefixCollisions() {
        assertThat(TitleHoursGateMatcher.tiedToHours("снять денег в банкомате", ROOTS)).isFalse();
        assertThat(TitleHoursGateMatcher.tiedToHours("купить банку кофе", ROOTS)).isFalse();
        assertThat(TitleHoursGateMatcher.tiedToHours("заказать банкетный зал", ROOTS)).isFalse();
        assertThat(TitleHoursGateMatcher.tiedToHours("проведать больного друга", ROOTS)).isFalse();
        assertThat(TitleHoursGateMatcher.tiedToHours("купить поликарбонат", ROOTS)).isFalse();
    }
}
