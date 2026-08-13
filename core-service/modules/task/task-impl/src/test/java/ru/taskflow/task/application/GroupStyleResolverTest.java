package ru.taskflow.task.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupStyleResolverTest {

    private final GroupStyleResolver resolver = new GroupStyleResolver();

    @Test
    void resolve_knownCategoryGivesExpectedPair() {
        assertThat(resolver.resolve("Покупки")).isEqualTo(new GroupStyleResolver.GroupStyle("orange", "shopping"));
        assertThat(resolver.resolve("Работа")).isEqualTo(new GroupStyleResolver.GroupStyle("blue", "briefcase"));
        assertThat(resolver.resolve("Здоровье")).isEqualTo(new GroupStyleResolver.GroupStyle("red", "pill"));
        assertThat(resolver.resolve("Дом")).isEqualTo(new GroupStyleResolver.GroupStyle("yellow", "home"));
        assertThat(resolver.resolve("Учёба")).isEqualTo(new GroupStyleResolver.GroupStyle("purple", "school"));
        assertThat(resolver.resolve("Финансы")).isEqualTo(new GroupStyleResolver.GroupStyle("green", "cash"));
        assertThat(resolver.resolve("Спорт")).isEqualTo(new GroupStyleResolver.GroupStyle("cyan", "run"));
        assertThat(resolver.resolve("Семья")).isEqualTo(new GroupStyleResolver.GroupStyle("orange", "users"));
    }

    @Test
    void resolve_caseAndWhitespaceInsensitive() {
        GroupStyleResolver.GroupStyle expected = resolver.resolve("работа");

        assertThat(resolver.resolve("РАБОТА")).isEqualTo(expected);
        assertThat(resolver.resolve("  Работа  ")).isEqualTo(expected);
        assertThat(resolver.resolve("рАбота")).isEqualTo(expected);
    }

    @Test
    void resolve_unknownNameGivesNonBlankColorAndFolderIcon() {
        GroupStyleResolver.GroupStyle style = resolver.resolve("Совершенно новая категория");

        assertThat(style.color()).isNotBlank();
        assertThat(style.icon()).isEqualTo("folder");
    }

    @Test
    void resolve_unknownNameIsDeterministic() {
        GroupStyleResolver.GroupStyle first = resolver.resolve("Мой уникальный проект");
        GroupStyleResolver.GroupStyle second = resolver.resolve("Мой уникальный проект");

        assertThat(first).isEqualTo(second);
    }
}
