package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightGame;
import cn.friendpoker.domain.flight.FlightPersistenceState;
import cn.friendpoker.domain.flight.FlightPhase;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;
import cn.friendpoker.server.room.RoomStatus;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Repository
public class FlightRoomStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public FlightRoomStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void insert(FlightRoom room) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into flight_rooms
                    (id, code, name, password_hash, host_username, status, state_json, state_version, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, room.id().toString(), room.code(), room.name(), room.passwordHash(), room.hostUsername(),
                    room.status().name(), serialize(new PersistedFlightRoomState(room.game().exportState(),
                            room.recentActionIds(), room.bots(), room.botSettings())), room.stateVersion(), Timestamp.from(room.createdAt()),
                    Timestamp.from(room.createdAt()));
            jdbc.update("insert into flight_room_members (room_id, username, joined_at) values (?, ?, ?)",
                    room.id().toString(), room.hostUsername(), Timestamp.from(room.createdAt()));
        });
    }

    public void addMember(UUID roomId, String username) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from flight_room_members where room_id = ? and username = ?", Integer.class,
                roomId.toString(), username);
        if (exists != null && exists == 0) {
            jdbc.update("insert into flight_room_members (room_id, username, joined_at) values (?, ?, ?)",
                    roomId.toString(), username, Timestamp.from(Instant.now()));
        }
    }

    public long saveState(FlightRoom room, FlightGame candidate, List<String> actionIds, RoomStatus status) {
        return saveState(room, candidate, actionIds, status, room.bots(), room.botSettings());
    }

    public long saveState(FlightRoom room, FlightGame candidate, List<String> actionIds, RoomStatus status,
                          List<BotSeat> bots, BotSettings settings) {
        Long next = transactions.execute(transaction -> {
            FlightPersistenceState gameState = candidate.exportState();
            long nextVersion = room.stateVersion() + 1;
            int updated = jdbc.update("""
                    update flight_rooms set state_json = ?, state_version = ?, status = ?, updated_at = ?
                    where id = ? and state_version = ?
                    """, serialize(new PersistedFlightRoomState(gameState, actionIds, bots, settings)), nextVersion, status.name(),
                    Timestamp.from(Instant.now()), room.id().toString(), room.stateVersion());
            if (updated != 1) throw new ConcurrencyFailureException("房间状态已被其他操作更新");
            if (becameComplete(room.game().exportState(), gameState)) insertHistory(room, gameState);
            return nextVersion;
        });
        return next == null ? room.stateVersion() + 1 : next;
    }

    public List<FlightRoom> loadActive() {
        return jdbc.query("select * from flight_rooms where status = 'ACTIVE' order by created_at", this::mapRoom);
    }

    public boolean codeExists(String code) {
        Integer count = jdbc.queryForObject("select count(*) from flight_rooms where code = ?", Integer.class, code);
        return count != null && count > 0;
    }

    private FlightRoom mapRoom(ResultSet rs, int rowNum) throws SQLException {
        UUID id = UUID.fromString(rs.getString("id"));
        PersistedFlightRoomState saved = deserialize(rs.getString("state_json"));
        List<String> members = jdbc.queryForList(
                "select username from flight_room_members where room_id = ? order by joined_at",
                String.class, id.toString());
        FlightRoom room = new FlightRoom(id, rs.getString("code"), rs.getString("name"), rs.getString("password_hash"),
                rs.getString("host_username"), FlightGame.restore(saved.game()), new LinkedHashSet<>(members),
                saved.recentActionIds(), rs.getTimestamp("created_at").toInstant(),
                RoomStatus.valueOf(rs.getString("status")), rs.getLong("state_version"), true);
        room.installBots(saved.bots(), saved.botSettings());
        return room;
    }

    private void insertHistory(FlightRoom room, FlightPersistenceState state) {
        String recordId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into hand_records
                (id, room_id, hand_number, summary, completed_at, room_name, game_type)
                values (?, ?, ?, ?, ?, ?, 'FLIGHT')
                """, recordId, room.id().toString(), state.gameNumber(), String.join("\n", state.messages()),
                Timestamp.from(Instant.now()), room.name());
        state.players().stream().map(player -> player.id()).distinct().filter(username -> !room.isBot(username)).forEach(username -> jdbc.update(
                "insert into hand_record_players (hand_id, username) values (?, ?)", recordId, username));
    }

    private static boolean becameComplete(FlightPersistenceState before, FlightPersistenceState after) {
        return after.phase() == FlightPhase.COMPLETE
                && (before.phase() != FlightPhase.COMPLETE || before.gameNumber() != after.gameNumber());
    }

    private String serialize(PersistedFlightRoomState state) {
        try {
            return json.writeValueAsString(state);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法保存飞行棋状态", exception);
        }
    }

    private PersistedFlightRoomState deserialize(String value) {
        try {
            return json.readValue(value, PersistedFlightRoomState.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法恢复飞行棋状态", exception);
        }
    }
}
