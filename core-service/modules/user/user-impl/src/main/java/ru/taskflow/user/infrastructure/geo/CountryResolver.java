package ru.taskflow.user.infrastructure.geo;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;

/**
 * Определяет страну по IP через локальную базу DB-IP Lite Country в формате
 * mmdb (файл не коммитим — скачивается контейнером при старте, см.
 * docker-entrypoint.sh). В отличие от MaxMind GeoLite2, DB-IP Lite не требует
 * регистрации и лицензионного ключа — читающая библиотека (com.maxmind.db)
 * от формата не зависит, кто базу собрал, ей всё равно. Если базы нет, путь
 * не задан или файл не читается — резолвер недоступен, и
 * {@link #resolveCountryIso} отдаёт пустой результат. Вызывающая сторона
 * обязана трактовать пустой результат как «страна неизвестна», а не как
 * «точно не Россия»: цена ошибки несимметрична.
 */
@Component
@Slf4j
public class CountryResolver implements HealthIndicator {

    private final String databasePath;
    private volatile DatabaseReader reader;
    private volatile String unavailableReason;

    public CountryResolver(@Value("${app.geoip.database-path:}") String databasePath) {
        this.databasePath = databasePath;
    }

    @PostConstruct
    void init() {
        reader = openReader();
    }

    @PreDestroy
    void close() {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException e) {
            log.warn("Не удалось закрыть базу geoip: {}", e.getMessage());
        }
    }

    private DatabaseReader openReader() {
        if (databasePath == null || databasePath.isBlank()) {
            unavailableReason = "GEOIP_DB_PATH не задан";
            log.error("{} — определение страны по IP отключено, кнопка Telegram будет скрыта для всех", unavailableReason);
            return null;
        }
        File file = new File(databasePath);
        if (!file.exists()) {
            unavailableReason = "файла нет по пути " + databasePath;
            log.error("База geoip недоступна: {} — определение страны по IP отключено", unavailableReason);
            return null;
        }
        // Частая ловушка bind-монтирования: если на хосте по указанному пути
        // ничего нет, Docker вместо ошибки создаёт там пустой каталог — и
        // в контейнере, и на хосте. Раньше это выглядело как «файл не
        // найден» неотличимо от опечатки в пути, и разбор занимал время;
        // называем это прямо, а не общей фразой.
        if (file.isDirectory()) {
            unavailableReason = "по пути " + databasePath + " каталог, а не файл — похоже на docker bind-mount "
                    + "несуществующего на хосте файла (Docker создаёт директорию вместо ошибки)";
            log.error("{} — определение страны по IP отключено", unavailableReason);
            return null;
        }
        if (!file.canRead()) {
            unavailableReason = "файл по пути " + databasePath + " недоступен для чтения (права доступа)";
            log.error("{} — определение страны по IP отключено", unavailableReason);
            return null;
        }
        try {
            return new DatabaseReader.Builder(file).build();
        } catch (IOException e) {
            unavailableReason = "не удалось открыть файл по пути " + databasePath + ": " + e.getMessage();
            log.error("{} — определение страны по IP отключено", unavailableReason);
            return null;
        }
    }

    public Optional<String> resolveCountryIso(String ip) {
        DatabaseReader currentReader = reader;
        if (currentReader == null || ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            return Optional.ofNullable(currentReader.country(address).getCountry().getIsoCode());
        } catch (GeoIp2Exception | IOException e) {
            log.warn("Не удалось определить страну по адресу: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Health health() {
        return reader == null
                ? Health.down().withDetail("reason", unavailableReason != null ? unavailableReason : "база geoip недоступна").build()
                : Health.up().build();
    }
}
