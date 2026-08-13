package ru.taskflow.app.migration;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import ru.taskflow.task.application.GroupStyleResolver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Проставляет color/icon существующим группам с пустыми полями той же
 * GroupStyleResolver, что использует автосоздание в TaskServiceImpl —
 * чтобы старые и новые группы получали одинаковый результат по одному
 * и тому же названию, без дублирования словаря в SQL.
 */
public class BackfillGroupStyleChange implements CustomTaskChange {

    private final GroupStyleResolver resolver = new GroupStyleResolver();

    @Override
    public void execute(Database database) throws CustomChangeException {
        Connection connection = ((JdbcConnection) database.getConnection()).getUnderlyingConnection();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, name FROM groups WHERE color IS NULL OR icon IS NULL");
             ResultSet rs = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE groups SET color = ?, icon = ? WHERE id = ?")) {
            while (rs.next()) {
                UUID id = rs.getObject("id", UUID.class);
                String name = rs.getString("name");
                GroupStyleResolver.GroupStyle style = resolver.resolve(name);
                update.setString(1, style.color());
                update.setString(2, style.icon());
                update.setObject(3, id);
                update.addBatch();
            }
            update.executeBatch();
        } catch (SQLException e) {
            throw new CustomChangeException("не удалось проставить color/icon существующим группам", e);
        }
    }

    @Override
    public String getConfirmationMessage() {
        return "color/icon проставлены существующим группам с пустыми полями";
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
