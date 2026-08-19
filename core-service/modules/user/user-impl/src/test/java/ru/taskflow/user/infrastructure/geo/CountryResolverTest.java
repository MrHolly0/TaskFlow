package ru.taskflow.user.infrastructure.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CountryResolverTest {

    @Test
    void resolveCountryIso_pathNotConfigured_returnsEmptyAndHealthIsDown() {
        CountryResolver resolver = new CountryResolver("");
        resolver.init();

        assertThat(resolver.resolveCountryIso("8.8.8.8")).isEmpty();
        assertThat(resolver.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void resolveCountryIso_fileDoesNotExist_returnsEmptyAndHealthIsDown() {
        CountryResolver resolver = new CountryResolver("/no/such/geolite2.mmdb");
        resolver.init();

        assertThat(resolver.resolveCountryIso("8.8.8.8")).isEmpty();
        assertThat(resolver.health().getStatus()).isEqualTo(Status.DOWN);
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
