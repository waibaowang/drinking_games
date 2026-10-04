package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.ActionType;
import cn.friendpoker.domain.game.GameRuleException;
import cn.friendpoker.domain.game.HoldemTable;
import cn.friendpoker.domain.game.PlayerSnapshot;
import cn.friendpoker.domain.game.Street;
import cn.friendpoker.domain.game.TableSnapshot;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Service
public class GameCoordinator {
    private static final Logger log = LoggerFactory.getLogger(GameCoordinator.class);
    private final RoomService rooms;
    private final SimpMessagingTemplate messaging;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "poker-action-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, TimerState> timers = new ConcurrentHashMap<>();

    public GameCoordinator(RoomService rooms, SimpMessagingTemplate messaging) {
        this.rooms = rooms;
        this.messaging = messaging;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeRecoveredRooms() {
        rooms.allInMemory().stream().filter(room -> room.status() == RoomStatus.ACTIVE).forEach(room -> {
            if (room.table().snapshotFor("").street() == Street.SHUFFLE_PENDING) {
                scheduleDeal(room, room.table().snapshotFor("").version(), 200);
            } else {
                scheduleTimeout(room);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public RoomState state(UUID roomId, String viewer) {
        return toState(rooms.requireMember(roomId, viewer), viewer);
    }

    public void sit(UUID roomId, String username, String displayName, int seat) {
        mutate(roomId, username, null, table -> table.sitDown(username, displayName, seat,
                rooms.get(roomId).config().initialStack()), "SIT");
    }

    public void stand(UUID roomId, String username) {
        mutate(roomId, username, null, table -> table.standUp(username), "STAND");
    }

    public void start(UUID roomId, String username) {
        PokerRoom room = rooms.requireMember(roomId, username);
        long preparedVersion;
        synchronized (room) {
            RoomService.requireActive(room);
            if (!room.hostUsername().equals(username)) throw new IllegalArgumentException("只有房主可以开始下一手牌");
            HoldemTable candidate = cloneTable(room);
            candidate.prepareHand();
            install(room, candidate, room.recentActionIds(), null, RoomStatus.ACTIVE);
            preparedVersion = candidate.snapshotFor("").version();
            log.info("room={} hand={} user={} operation=START_COMMIT accepted", room.id(),
                    candidate.snapshotFor("").handNumber(), username);
        }
        cancelTimer(room.id());
        TableSnapshot committed = room.table().snapshotFor("");
        room.members().forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/rooms/" + room.id() + "/shuffle",
                new ShuffleCommitment(committed.handNumber(), committed.shuffleCommitment(), committed.version())));
        broadcast(room);
        scheduleDeal(room, preparedVersion, 500);
    }

    public void act(UUID roomId, String username, String actionId, ActionType action, long amount, long version) {
        if (actionId == null || actionId.isBlank() || actionId.length() > 64) {
            throw new IllegalArgumentException("actionId 不合法");
        }
        PokerRoom room = rooms.requireMember(roomId, username);
        boolean duplicate;
        synchronized (room) {
            RoomService.requireActive(room);
            duplicate = room.hasActionId(actionId);
            if (!duplicate) {
                HoldemTable candidate = cloneTable(room);
                candidate.act(username, action, amount, version);
                List<String> actionIds = room.actionIdsWith(actionId);
                install(room, candidate, actionIds, nextDeadline(room, candidate), RoomStatus.ACTIVE);
                log.info("room={} hand={} user={} actionId={} operation={} accepted", room.id(),
                        candidate.snapshotFor("").handNumber(), username, actionId, action);
            }
        }
        if (duplicate) {
            log.info("room={} user={} actionId={} operation={} duplicate", room.id(), username, actionId, action);
        }
        broadcast(room);
        scheduleTimeout(room);
    }

    public void close(UUID roomId, String adminUsername) {
        PokerRoom room = rooms.get(roomId);
        synchronized (room) {
            if (room.status() == RoomStatus.CLOSED) return;
            install(room, cloneTable(room), room.recentActionIds(), null, RoomStatus.CLOSED);
            log.info("room={} hand={} user={} operation=ADMIN_CLOSE accepted", room.id(),
                    room.table().snapshotFor("").handNumber(), adminUsername);
        }
        cancelTimer(room.id());
        broadcast(room);
    }

    public void broadcast(UUID roomId) {
        broadcast(rooms.get(roomId));
    }

    public void chat(UUID roomId, String username, String displayName, String text) {
        PokerRoom room = rooms.requireMember(roomId, username);
        RoomService.requireActive(room);
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty() || cleaned.length() > 200) {
            throw new IllegalArgumentException("聊天内容长度应为 1 到 200 字");
        }
        ChatMessage message = new ChatMessage(username, displayName, cleaned, Instant.now());
        room.members().forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/rooms/" + room.id() + "/chat", message));
    }

    private void mutate(UUID roomId, String username, String actionId, Consumer<HoldemTable> operation, String label) {
        PokerRoom room = rooms.requireMember(roomId, username);
        synchronized (room) {
            RoomService.requireActive(room);
            HoldemTable candidate = cloneTable(room);
            operation.accept(candidate);
            install(room, candidate, room.actionIdsWith(actionId), nextDeadline(room, candidate), RoomStatus.ACTIVE);
            log.info("room={} hand={} user={} operation={} accepted", room.id(),
                    candidate.snapshotFor("").handNumber(), username, label);
        }
        broadcast(room);
        scheduleTimeout(room);
    }

    private HoldemTable cloneTable(PokerRoom room) {
        return HoldemTable.restore(room.config(), room.table().exportState());
    }

    private void install(PokerRoom room, HoldemTable candidate, List<String> actionIds, Instant deadline,
                         RoomStatus status) {
        long nextStateVersion = rooms.store().saveState(room, candidate, actionIds, deadline, status);
        room.install(candidate, actionIds, deadline, status, nextStateVersion);
    }

    private Instant nextDeadline(PokerRoom room, HoldemTable candidate) {
        return candidate.snapshotFor("").actorSeat() == null
                ? null : Instant.now().plusSeconds(room.config().actionSeconds());
    }

    private void scheduleDeal(PokerRoom room, long expectedVersion, long delayMillis) {
        scheduler.schedule(() -> deal(room.id(), expectedVersion), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void deal(UUID roomId, long expectedVersion) {
        PokerRoom room;
        try {
            room = rooms.get(roomId);
            synchronized (room) {
                if (room.status() != RoomStatus.ACTIVE) return;
                TableSnapshot current = room.table().snapshotFor("");
                if (current.version() != expectedVersion || current.street() != Street.SHUFFLE_PENDING) return;
                HoldemTable candidate = cloneTable(room);
                candidate.dealPreparedHand();
                install(room, candidate, room.recentActionIds(), nextDeadline(room, candidate), RoomStatus.ACTIVE);
            }
            broadcast(room);
            scheduleTimeout(room);
        } catch (GameRuleException exception) {
            log.info("room={} operation=DEAL skipped reason={}", roomId, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("room={} operation=DEAL failed", roomId, exception);
        }
    }

    private void scheduleTimeout(PokerRoom room) {
        cancelTimer(room.id());
        TableSnapshot snapshot = room.table().snapshotFor("");
        if (room.status() != RoomStatus.ACTIVE || snapshot.actorSeat() == null || room.actionDeadline() == null) return;
        long delay = Math.max(0, Duration.between(Instant.now(), room.actionDeadline()).toMillis());
        ScheduledFuture<?> future = scheduler.schedule(() -> timeout(room.id(), snapshot.version()),
                delay, TimeUnit.MILLISECONDS);
        timers.put(room.id(), new TimerState(snapshot.version(), room.actionDeadline(), future));
    }

    private void cancelTimer(UUID roomId) {
        TimerState previous = timers.remove(roomId);
        if (previous != null) previous.future().cancel(false);
    }

    private void timeout(UUID roomId, long expectedVersion) {
        PokerRoom room;
        try {
            room = rooms.get(roomId);
            synchronized (room) {
                if (room.status() != RoomStatus.ACTIVE) return;
                TableSnapshot snapshot = room.table().snapshotFor("");
                if (snapshot.version() != expectedVersion || snapshot.actorSeat() == null) return;
                PlayerSnapshot actor = snapshot.players().stream()
                        .filter(player -> player.seat() == snapshot.actorSeat()).findFirst().orElseThrow();
                ActionType fallback = room.table().snapshotFor(actor.id()).legalActions().contains(ActionType.CHECK)
                        ? ActionType.CHECK : ActionType.FOLD;
                HoldemTable candidate = cloneTable(room);
                candidate.act(actor.id(), fallback, 0, expectedVersion);
                String actionId = "timeout:" + roomId + ":" + expectedVersion;
                install(room, candidate, room.actionIdsWith(actionId), nextDeadline(room, candidate), RoomStatus.ACTIVE);
                log.info("room={} hand={} user={} actionId={} operation=TIMEOUT_{} accepted", room.id(),
                        candidate.snapshotFor("").handNumber(), actor.id(), actionId, fallback);
            }
            broadcast(room);
            scheduleTimeout(room);
        } catch (GameRuleException exception) {
            log.info("room={} operation=TIMEOUT skipped reason={}", roomId, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("room={} operation=TIMEOUT failed", roomId, exception);
        }
    }

    private void broadcast(PokerRoom room) {
        room.members().forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/rooms/" + room.id(), toState(room, member)));
    }

    private RoomState toState(PokerRoom room, String viewer) {
        return new RoomState(room.id(), room.code(), room.name(), room.hostUsername(), room.config(),
                room.table().snapshotFor(viewer), room.actionDeadline(), room.status(), room.recovered());
    }

    private record TimerState(long version, Instant deadline, ScheduledFuture<?> future) {}
    public record ChatMessage(String username, String displayName, String text, Instant sentAt) {}
    public record ShuffleCommitment(long handNumber, String commitment, long version) {}
}
