package ru.taskflow.user.application;

import java.util.Optional;

/**
 * Приведение российского номера к E.164 (+7XXXXXXXXXX). Только Россия —
 * вход по телефону завязан на 152-ФЗ/199-ФЗ и рассчитан на российских
 * пользователей, поэтому нет смысла тащить universal-парсер вроде
 * libphonenumber ради этой одной формы.
 */
public final class PhoneNumberNormalizer {

    private PhoneNumberNormalizer() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("8")) {
            digits = "7" + digits.substring(1);
        } else if (digits.length() == 10) {
            digits = "7" + digits;
        }
        if (digits.length() != 11 || !digits.startsWith("7")) {
            return Optional.empty();
        }
        return Optional.of("+" + digits);
    }
}
