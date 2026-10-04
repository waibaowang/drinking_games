package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.ActionType;
import cn.friendpoker.domain.game.PlayerSnapshot;
import cn.friendpoker.domain.game.Street;
import cn.friendpoker.domain.game.TableSnapshot;
import cn.friendpoker.server.history.HandRecord;
import cn.friendpoker.server.history.HandRecordRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class GameCoordinator {
    private final RoomService rooms;
    private final SimpMessagingTemplate messaging;
    private final HandRecordRepository handRecords;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "poker-action-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, TimerState> timers = new ConcurrentHashMap<>();
    private final Set<String> persistedHands = ConcurrentHashMap.newKeySet();

    public GameCoordinator(RoomService rooms, SimpMessagingTemplate messaging, HandRecordRepository handRecords) {
        this.rooms = rooms;
        this.messaging = messaging;
        this.handRecords = handRecords;
    }

    public RoomState state(UUID roomId, String viewer) {
        PokerRoom room = rooms.requireMember(roomId, viewer);
        return toState(room, viewer);
    }

    public void sit(UUID roomId, String username, String displayName, int seat) {
        PokerRoom room = rooms.requireMember(roomId, username);
        room.table().sitDown(username, displayName, seat, room.config().initialStack());
        broadcast(room);
    }

    public void stand(UUID roomId, String username) {
        PokerRoom room = rooms.requireMember(roomId, username);
        room.table().standUp(username);
        broadcast(room);
    }

    public void start(UUID roomId, String username) {
        PokerRoom room = rooms.requireMember(roomId, username);
        if (!room.hostUsername().equals(username)) {
            throw new IllegalArgumentException("只有房主可以开始下一手牌");
        }
        room.table().startHand();
        broadcast(room);
    }

    public void act(UUID roomId, String username, ActionType action, long amount, long version) {
        PokerRoom room = rooms.requireMember(roomId, username);
        room.table().act(username, action, amount, version);
        broadcast(room);
    }

    public void broadcast(UUID roomId) {
        broadcast(rooms.get(roomId));
    }

    public void chat(UUID roomId, String username, String displayName, String text) {
        PokerRoom room = rooms.requireMember(roomId, username);
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty() || cleaned.length() > 200) {
            throw new IllegalArgumentException("聊天内容长度应为 1 到 200 字");
        }
        ChatMessage message = new ChatMessage(username, displayName, cleaned, Instant.now());
        room.members().forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/rooms/" + room.id() + "/chat", message));
    }

    private void broadcast(PokerRoom room) {
        scheduleTimeout(room);
        persistCompletedHand(room);
        for (String member : room.members()) {
            messaging.convertAndSendToUser(member, "/queue/rooms/" + room.id(), toState(room, member));
        }
    }

    private RoomState toState(PokerRoom room, String viewer) {
        TimerState timer = timers.get(room.id());
        return new RoomState(room.id(), room.code(), room.name(), room.hostUsername(), room.config(),
                room.table().snapshotFor(viewer), timer == null ? null : timer.deadline());
    }

    private void scheduleTimeout(PokerRoom room) {
        TableSnapshot snapshot = room.table().snapshotFor("");
        TimerState existing = timers.get(room.id());
        if (snapshot.actorSeat() == null) {
            if (existing != null) existing.future().cancel(false);
            timers.remove(room.id());
            return;
        }
        if (existing != null && existing.version() == snapshot.version()) {
            return;
        }
        if (existing != null) existing.future().cancel(false);
        Instant deadline = Instant.now().plusSeconds(room.config().actionSeconds());
        ScheduledFuture<?> future = scheduler.schedule(() -> timeout(room.id(), snapshot.version()),
                room.config().actionSeconds(), TimeUnit.SECONDS);
        timers.put(room.id(), new TimerState(snapshot.version(), deadline, future));
    }

    private void timeout(UUID roomId, long expectedVersion) {
        try {
            PokerRoom room = rooms.get(roomId);
            TableSnapshot snapshot = room.table().snapshotFor("");
            if (snapshot.version() != expectedVersion || snapshot.actorSeat() == null) return;
            PlayerSnapshot actor = snapshot.players().stream().filter(player -> player.seat() == snapshot.actorSeat())
                    .findFirst().orElseThrow();
            ActionType fallback = room.table().snapshotFor(actor.id()).legalActions().contains(ActionType.CHECK)
                    ? ActionType.CHECK : ActionType.FOLD;
            room.table().act(actor.id(), fallback, 0, expectedVersion);
            broadcast(room);
        } catch (RuntimeException ignored) {
            // Another action won the race with the timeout; the latest broadcast owns the next timer.
        }
    }

    private void persistCompletedHand(PokerRoom room) {
        TableSnapshot snapshot = room.table().snapshotFor("");
        if (snapshot.street() != Street.COMPLETE || snapshot.handNumber() == 0) return;
        String key = room.id() + ":" + snapshot.handNumber();
        if (persistedHands.add(key)) {
            handRecords.save(new HandRecord(room.id(), snapshot.handNumber(), String.join("\n", snapshot.messages())));
        }
    }

    private record TimerState(long version, Instant deadline, ScheduledFuture<?> future) {}
    public record ChatMessage(String username, String displayName, String text, Instant sentAt) {}
}
