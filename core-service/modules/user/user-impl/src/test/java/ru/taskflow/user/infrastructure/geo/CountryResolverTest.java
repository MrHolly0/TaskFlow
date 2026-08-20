package ru.taskflow.user.infrastructure.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class CountryResolverTest {

    @Test
    void resolveCountryIso_pathNotConfigured_returnsEmptyAndHealthIsDown() {
        CountryResolver resolver = new CountryResolver("");
        resolver.init();

        assertThat(resolver.resolveCountryIso("8.8.8.8")).isEmpty();
        assertThat(resolver.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(resolver.health().getDetails().get("reason").toString()).contains("GEOIP_DB_PATH не задан");
    }

    @Test
    void resolveCountryIso_fileDoesNotExist_returnsEmptyAndHealthIsDown() {
        CountryResolver resolver = new CountryResolver("/no/such/geolite2.mmdb");
        resolver.init();

        assertThat(resolver.resolveCountryIso("8.8.8.8")).isEmpty();
        assertThat(resolver.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(resolver.health().getDetails().get("reason").toString()).contains("файла нет по пути");
    }

    // Ловушка docker bind-mount: если файла на хосте нет, Docker создаёt на
    // его месте каталог вместо ошибки — раньше это выглядело так же, как
    // «файл не найден», и владелец полчаса разбирался, что сломано на самом
    // деле. Сообщение обязано называть каталог каталогом.
    @Test
    void resolveCountryIso_pathIsDirectory_returnsEmptyAndHealthReasonNamesDirectory(@TempDir Path tempDir) {
        Path dirInsteadOfFile = tempDir.resolve("GeoLite2-Country.mmdb");
        assertThat(dirInsteadOfFile.toFile().mkdir()).isTrue();

        CountryResolver resolver = new CountryResolver(dirInsteadOfFile.toString());
        resolver.init();

        assertThat(resolver.resolveCountryIso("8.8.8.8")).isEmpty();
        assertThat(resolver.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(resolver.health().getDetails().get("reason").toString()).contains("каталог");
    }

    @Test
    void resolveCountryIso_fileNotReadable_returnsEmptyAndHealthReasonNamesPermissions(@TempDir Path tempDir) throws IOException {
        Path unreadable = tempDir.resolve("unreadable.mmdb");
        Files.writeString(unreadable, "irrelevant content");
        File file = unreadable.toFile();
        file.setReadable(false);
        // На некоторых средах (например под root) setReadable(false) не
        // действует — тогда проверка бессмысленна, а не ложно красная.
        assumeFalse(file.canRead());

        CountryResolver resolver = new CountryResolver(unreadable.toString());
        resolver.init();

        assertThat(resolver.resolveCountryIso("8.8.8.8")).isEmpty();
        assertThat(resolver.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(resolver.health().getDetails().get("reason").toString()).contains("недоступен для чтения");
    }

    @Test
    void resolveCountryIso_fileIsNotValidDatabase_returnsEmptyAndHealthIsDown(@TempDir Path tempDir) throws IOException {
        Path notAMmdb = tempDir.resolve("broken.mmdb");
        Files.writeString(notAMmdb, "not a real geoip database");

        CountryResolver resolver = new CountryResolver(notAMmdb.toString());
        resolver.init();

        assertThat(resolver.resolveCountryIso("8.8.8.8")).isEmpty();
        assertThat(resolver.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void resolveCountryIso_blankIp_returnsEmptyWithoutTouchingReader() {
        CountryResolver resolver = new CountryResolver("");
        resolver.init();

        assertThat(resolver.resolveCountryIso(null)).isEmpty();
        assertThat(resolver.resolveCountryIso("")).isEmpty();
    }
}
