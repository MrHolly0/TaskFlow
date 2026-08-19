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
 * Определяет страну по IP через локальную базу GeoLite2 (файл не коммитим —
 * лицензия MaxMind запрещает распространение, монтируется томом). Если базы
 * нет, путь не задан или файл не читается — резолвер недоступен, и
 * {@link #resolveCountryIso} отдаёт пустой результат. Вызывающая сторона
 * обязана трактовать пустой результат как «страна неизвестна», а не как
 * «точно не Россия»: цена ошибки несимметрична.
 */
@Component
@Slf4j
public class CountryResolver implements HealthIndicator {

    private final String databasePath;
    private volatile DatabaseReader reader;

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
            log.warn("Не удалось закрыть базу GeoLite2: {}", e.getMessage());
        }
    }

    private DatabaseReader openReader() {
        if (databasePath == null || databasePath.isBlank()) {
            log.error("GEOIP_DB_PATH не задан — определение страны по IP отключено, кнопка Telegram будет скрыта для всех");
            return null;
        }
        File file = new File(databasePath);
        if (!file.isFile()) {
            log.error("Файл базы GeoLite2 не найден по пути {} — определение страны по IP отключено", databasePath);
            return null;
        }
        try {
            return new DatabaseReader.Builder(file).build();
        } catch (IOException e) {
            log.error("Не удалось открыть базу GeoLite2 по пути {}: {}", databasePath, e.getMessage());
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
                ? Health.down().withDetail("reason", "база GeoLite2 недоступна").build()
                : Health.up().build();
    }
}
