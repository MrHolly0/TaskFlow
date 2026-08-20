package ru.taskflow.user.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumberNormalizerTest {

    @ParameterizedTest
    @CsvSource({
            "+7 999 123-45-67, +79991234567",
            "89991234567, +79991234567",
            "7(999)1234567, +79991234567",
            "9991234567, +79991234567",
            "+79991234567, +79991234567",
    })
    void normalize_validFormats_returnsE164(String raw, String expected) {
        assertThat(PhoneNumberNormalizer.normalize(raw)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "not-a-phone", "+1 999 123 45 67", "999123456", "999123456789"})
    void normalize_invalidFormats_returnsEmpty(String raw) {
        assertThat(PhoneNumberNormalizer.normalize(raw)).isEmpty();
    }

    @Test
    void normalize_null_returnsEmpty() {
        assertThat(PhoneNumberNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void normalize_blank_returnsEmpty() {
        assertThat(PhoneNumberNormalizer.normalize("   ")).isEmpty();
    }
}
