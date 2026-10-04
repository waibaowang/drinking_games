package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.HoldemTable;
import cn.friendpoker.domain.game.PlayerPersistenceState;
import cn.friendpoker.domain.game.Street;
import cn.friendpoker.domain.game.TableConfig;
import cn.friendpoker.domain.game.TablePersistenceState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class RoomStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public RoomStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void insert(PokerRoom room) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into poker_rooms
                    (id, code, name, password_hash, host_username, small_blind, big_blind, initial_stack,
                     max_players, action_seconds, status, state_json, state_version, action_deadline, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, room.id().toString(), room.code(), room.name(), room.passwordHash(), room.hostUsername(),
                    room.config().smallBlind(), room.config().bigBlind(), room.config().initialStack(),
                    room.config().maxPlayers(), room.config().actionSeconds(), room.status().name(),
                    serialize(new PersistedRoomState(room.table().exportState(), room.recentActionIds())),
                    room.stateVersion(), null, Timestamp.from(room.createdAt()), Timestamp.from(room.createdAt()));
            jdbc.update("insert into room_members (room_id, username, joined_at) values (?, ?, ?)",
                    room.id().toString(), room.hostUsername(), Timestamp.from(room.createdAt()));
        });
    }

    public void addMember(UUID roomId, String username) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from room_members where room_id = ? and username = ?", Integer.class,
                roomId.toString(), username);
        if (exists != null && exists == 0) {
            jdbc.update("insert into room_members (room_id, username, joined_at) values (?, ?, ?)",
                    roomId.toString(), username, Timestamp.from(Instant.now()));
        }
    }

    public long saveState(PokerRoom room, HoldemTable candidate, List<String> actionIds,
                          Instant deadline, RoomStatus status) {
        Long next = transactions.execute(transaction -> {
            TablePersistenceState tableState = candidate.exportState();
            long nextVersion = room.stateVersion() + 1;
            int updated = jdbc.update("""
                    update poker_rooms set state_json = ?, state_version = ?, action_deadline = ?, status = ?, updated_at = ?
                    where id = ? and state_version = ?
                    """, serialize(new PersistedRoomState(tableState, actionIds)), nextVersion,
                    deadline == null ? null : Timestamp.from(deadline), status.name(), Timestamp.from(Instant.now()),
                    room.id().toString(), room.stateVersion());
            if (updated != 1) throw new ConcurrencyFailureException("房间状态已被其他操作更新");
            if (becameComplete(room.table().exportState(), tableState)) {
                insertHistory(room, tableState);
            }
            return nextVersion;
        });
        return next == null ? room.stateVersion() + 1 : next;
    }

    public List<PokerRoom> loadActive() {
        return jdbc.query("select * from poker_rooms where status = 'ACTIVE' order by created_at", this::mapRoom);
    }

    private PokerRoom mapRoom(ResultSet rs, int rowNum) throws SQLException {
        UUID id = UUID.fromString(rs.getString("id"));
        TableConfig config = new TableConfig(rs.getLong("small_blind"), rs.getLong("big_blind"),
                rs.getLong("initial_stack"), rs.getInt("max_players"), rs.getInt("action_seconds"));
        PersistedRoomState saved = deserialize(rs.getString("state_json"));
        List<String> members = jdbc.queryForList(
                "select username from room_members where room_id = ? order by joined_at", String.class, id.toString());
        Timestamp deadline = rs.getTimestamp("action_deadline");
        return new PokerRoom(id, rs.getString("code"), rs.getString("name"), rs.getString("password_hash"),
                rs.getString("host_username"), config, HoldemTable.restore(config, saved.table()),
                new LinkedHashSet<>(members), saved.recentActionIds(), rs.getTimestamp("created_at").toInstant(),
                RoomStatus.valueOf(rs.getString("status")), deadline == null ? null : deadline.toInstant(),
                rs.getLong("state_version"), true);
    }

    private void insertHistory(PokerRoom room, TablePersistenceState state) {
        String handId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into hand_records (id, room_id, hand_number, summary, completed_at, room_name)
                values (?, ?, ?, ?, ?, ?)
                """, handId, room.id().toString(), state.handNumber(), String.join("\n", state.messages()),
                Timestamp.from(Instant.now()), room.name());
        state.players().stream().filter(player -> player.holeCards().size() == 2)
                .map(PlayerPersistenceState::id).distinct()
                .forEach(username -> jdbc.update(
                        "insert into hand_record_players (hand_id, username) values (?, ?)", handId, username));
    }

    private static boolean becameComplete(TablePersistenceState before, TablePersistenceState after) {
        return after.street() == Street.COMPLETE
                && (before.street() != Street.COMPLETE || before.handNumber() != after.handNumber());
    }

    private String serialize(PersistedRoomState state) {
        try {
            return json.writeValueAsString(state);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法保存牌桌状态", exception);
        }
    }

    private PersistedRoomState deserialize(String value) {
        try {
            return json.readValue(value, PersistedRoomState.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法恢复牌桌状态", exception);
        }
    }
}
