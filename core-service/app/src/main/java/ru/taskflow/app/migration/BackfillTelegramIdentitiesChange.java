package ru.taskflow.app.migration;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Заводит идентичность TELEGRAM каждому существующему пользователю
 * с непустым telegram_id — до этой миграции вход был возможен только
 * через Telegram, значит у всех таких пользователей телеграм есть.
 *
 * После вставки числа сверяются: идентичностей TELEGRAM должно быть
 * ровно столько же, сколько пользователей с telegram_id. Расхождение —
 * повод остановиться, а не тихо продолжить с неполными данными.
 */
public class BackfillTelegramIdentitiesChange implements CustomTaskChange {

    @Override
    public void execute(Database database) throws CustomChangeException {
        Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, telegram_id, created_at FROM users WHERE telegram_id IS NOT NULL");
             ResultSet rs = select.executeQuery();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO user_identities (id, user_id, provider, external_id, verified_at, created_at) "
                             + "VALUES (?, ?, 'TELEGRAM', ?, ?, ?)")) {
            int userCount = 0;
            while (rs.next()) {
                UUID userId = rs.getObject("id", UUID.class);
                long telegramId = rs.getLong("telegram_id");
                var createdAt = rs.getObject("created_at", java.time.OffsetDateTime.class);
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, userId);
                insert.setString(3, Long.toString(telegramId));
                insert.setObject(4, createdAt);
                insert.setObject(5, createdAt);
                insert.addBatch();
                userCount++;
            }
            insert.executeBatch();

            int identityCount = countTelegramIdentities(connection);
            if (identityCount != userCount) {
                throw new CustomChangeException(
                        "расхождение при переносе идентичностей telegram: пользователей с telegram_id — "
                                + userCount + ", созданных идентичностей TELEGRAM — " + identityCount);
            }
        } catch (SQLException e) {
            throw new CustomChangeException("не удалось перенести telegram_id в user_identities", e);
        }
    }

    private int countTelegramIdentities(Connection connection) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement(
                "SELECT COUNT(*) FROM user_identities WHERE provider = 'TELEGRAM'");
             ResultSet rs = count.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Override
    public String getConfirmationMessage() {
        return "идентичности TELEGRAM заведены для существующих пользователей";
    }

    @Override
    public void setUp() throws SetupException {
        // конфигурации не требует
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
        // доступ к файлам не требуется
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
