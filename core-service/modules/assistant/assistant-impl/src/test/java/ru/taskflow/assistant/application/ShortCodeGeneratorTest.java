package ru.taskflow.assistant.application;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void generate_returnsEightCharacters() {
        assertThat(generator.generate()).hasSize(8);
    }

    @Test
    void generate_avoidsAmbiguousCharacters() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.generate()).doesNotContainAnyWhitespaces()
                    .doesNotContain("0").doesNotContain("O")
                    .doesNotContain("1").doesNotContain("I").doesNotContain("L");
        }
    }

    @Test
    void generate_producesDistinctValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(generator.generate());
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    void generate_fitsTelegramCallbackBudget() {
        String callbackData = "asst:apply:" + generator.generate();

        assertThat(callbackData.getBytes()).hasSizeLessThanOrEqualTo(64);
    }
}
