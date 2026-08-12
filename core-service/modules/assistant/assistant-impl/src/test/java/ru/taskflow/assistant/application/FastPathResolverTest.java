package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FastPathResolverTest {

    private final FastPathResolver resolver = new FastPathResolver();

    @Test
    void resolve_appliesOnShortConfirmation() {
        assertThat(resolver.resolve("да", true)).contains(true);
        assertThat(resolver.resolve("+", true)).contains(true);
        assertThat(resolver.resolve("ок", true)).contains(true);
        assertThat(resolver.resolve("окей", true)).contains(true);
        assertThat(resolver.resolve("готово", true)).contains(true);
        assertThat(resolver.resolve("применяй", true)).contains(true);
        assertThat(resolver.resolve("давай", true)).contains(true);
    }

    @Test
    void resolve_rejectsOnShortRejection() {
        assertThat(resolver.resolve("нет", true)).contains(false);
        assertThat(resolver.resolve("-", true)).contains(false);
        assertThat(resolver.resolve("отмена", true)).contains(false);
        assertThat(resolver.resolve("отмени", true)).contains(false);
        assertThat(resolver.resolve("не надо", true)).contains(false);
    }

    @Test
    void resolve_ignoresLongTextContainingYes() {
        assertThat(resolver.resolve("да, и ещё купи хлеб пожалуйста", true)).isEmpty();
    }

    @Test
    void resolve_ignoresWhenNoPendingProposal() {
        assertThat(resolver.resolve("да", false)).isEmpty();
        assertThat(resolver.resolve("нет", false)).isEmpty();
    }

    @Test
    void resolve_isCaseAndPunctuationInsensitive() {
        assertThat(resolver.resolve("Да!", true)).contains(true);
        assertThat(resolver.resolve("ГОТОВО.", true)).contains(true);
        assertThat(resolver.resolve("Нет.", true)).contains(false);
        assertThat(resolver.resolve("  ок  ", true)).contains(true);
    }

    @Test
    void resolve_ignoresSubstringMatch() {
        // короткие, чтобы отдельно от потолка длины проверить именно совпадение целиком
        assertThat(resolver.resolve("ерунда", true)).isEmpty();
        assertThat(resolver.resolve("давайте", true)).isEmpty();
        assertThat(resolver.resolve("нет времени купить молоко", true)).isEmpty();
    }
}
