package cn.friendpoker.server;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationUpgradeTest {
    @Test
    void upgradesV1UsersAndHistoryWithoutDataLoss() throws Exception {
        String url = "jdbc:h2:mem:migration-upgrade;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("1").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            statement.executeUpdate("insert into user_accounts values ('u1','old_user','hash','旧玩家',current_timestamp)");
            statement.executeUpdate("insert into hand_records values ('h1','room-1',1,'旧记录',current_timestamp)");
        }

        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", ""); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("select role, enabled from user_accounts where id='u1'")) {
                result.next();
                assertEquals("PLAYER", result.getString(1));
                assertEquals(true, result.getBoolean(2));
            }
            try (var result = statement.executeQuery("select room_name from hand_records where id='h1'")) {
                result.next();
                assertEquals("旧牌局", result.getString(1));
            }
        }
    }
}
