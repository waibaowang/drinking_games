package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightGame;
import cn.friendpoker.domain.flight.FlightSnapshot;
import cn.friendpoker.server.room.GameCoordinator.ChatMessage;
import cn.friendpoker.server.room.GameType;
import cn.friendpoker.server.room.RoomStatus;
import cn.friendpoker.server.user.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class FlightCoordinator {
    private static final Logger log = LoggerFactory.getLogger(FlightCoordinator.class);
    private final FlightRoomService rooms;
    private final SimpMessagingTemplate messaging;
    private final UserAccountRepository users;

    public FlightCoordinator(FlightRoomService rooms, SimpMessagingTemplate messaging, UserAccountRepository users) {
        this.rooms = rooms;
        this.messaging = messaging;
        this.users = users;
    }

    public FlightRoomState state(UUID roomId, String viewer) {
        return toState(rooms.requireMember(roomId, viewer), viewer);
    }

    public void sit(UUID roomId, String username, String displayName, int seat) {
        mutate(roomId, username, null, game -> game.sitDown(username, displayName, seat), "SIT");
    }

    public void stand(UUID roomId, String username) {
        mutate(roomId, username, null, game -> game.standUp(username), "STAND");
    }

    public void start(UUID roomId, String username) {
        FlightRoom room = rooms.requireMember(roomId, username);
        synchronized (room) {
            FlightRoomService.requireActive(room);
            if (!room.hostUsername().equals(username)) throw new IllegalArgumentException("只有房主可以开始下一局");
            FlightGame candidate = cloneGame(room);
            candidate.start();
            install(room, candidate, room.recentActionIds(), RoomStatus.ACTIVE);
            log.info("room={} game={} user={} operation=FLIGHT_START accepted", room.id(),
                    candidate.snapshotFor("").gameNumber(), username);
        }
        broadcast(room);
    }

    public void act(UUID roomId, String username, String actionId, String action, Integer pieceIndex, long version) {
        if (actionId == null || actionId.isBlank() || actionId.length() > 64) {
            throw new IllegalArgumentException("actionId 不合法");
        }
        FlightRoom room = rooms.requireMember(roomId, username);
        boolean duplicate;
        synchronized (room) {
            FlightRoomService.requireActive(room);
            duplicate = room.hasActionId(actionId);
            if (!duplicate) {
                FlightGame candidate = cloneGame(room);
                if ("ROLL".equals(action)) candidate.roll(username, version);
                else if ("MOVE".equals(action) && pieceIndex != null) candidate.move(username, pieceIndex, version);
                else throw new IllegalArgumentException("飞行棋操作不合法");
                install(room, candidate, room.actionIdsWith(actionId), RoomStatus.ACTIVE);
                log.info("room={} game={} user={} actionId={} operation=FLIGHT_{} accepted", room.id(),
                        candidate.snapshotFor("").gameNumber(), username, actionId, action);
            }
        }
        if (duplicate) log.info("room={} user={} actionId={} operation=FLIGHT_{} duplicate",
                room.id(), username, actionId, action);
        broadcast(room);
    }

    public void close(UUID roomId, String adminUsername) {
        FlightRoom room = rooms.get(roomId);
        synchronized (room) {
            if (room.status() == RoomStatus.CLOSED) return;
            install(room, cloneGame(room), room.recentActionIds(), RoomStatus.CLOSED);
            log.info("room={} game={} user={} operation=FLIGHT_ADMIN_CLOSE accepted", room.id(),
                    room.game().snapshotFor("").gameNumber(), adminUsername);
        }
        broadcast(room);
    }

    public void broadcast(UUID roomId) { broadcast(rooms.get(roomId)); }

    public void chat(UUID roomId, String username, String displayName, String text) {
        FlightRoom room = rooms.requireMember(roomId, username);
        FlightRoomService.requireActive(room);
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isEmpty() || cleaned.length() > 200) throw new IllegalArgumentException("聊天内容长度应为 1 到 200 字");
        ChatMessage message = new ChatMessage(username, displayName, cleaned, Instant.now());
        activeMembers(room).forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/flight-rooms/" + room.id() + "/chat", message));
    }

    private void mutate(UUID roomId, String username, String actionId, Consumer<FlightGame> operation, String label) {
        FlightRoom room = rooms.requireMember(roomId, username);
        synchronized (room) {
            FlightRoomService.requireActive(room);
            FlightGame candidate = cloneGame(room);
            operation.accept(candidate);
            install(room, candidate, room.actionIdsWith(actionId), RoomStatus.ACTIVE);
            log.info("room={} game={} user={} operation=FLIGHT_{} accepted", room.id(),
                    candidate.snapshotFor("").gameNumber(), username, label);
        }
        broadcast(room);
    }

    private FlightGame cloneGame(FlightRoom room) { return FlightGame.restore(room.game().exportState()); }

    private void install(FlightRoom room, FlightGame candidate, List<String> actionIds, RoomStatus status) {
        long nextVersion = rooms.store().saveState(room, candidate, actionIds, status);
        room.install(candidate, actionIds, status, nextVersion);
    }

    private void broadcast(FlightRoom room) {
        activeMembers(room).forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/flight-rooms/" + room.id(), toState(room, member)));
    }

    private List<String> activeMembers(FlightRoom room) {
        return room.members().stream().filter(member -> users.findByUsernameIgnoreCase(member)
                .map(account -> account.isEnabled()).orElse(false)).toList();
    }

    private FlightRoomState toState(FlightRoom room, String viewer) {
        FlightSnapshot snapshot = room.game().snapshotFor(viewer);
        return new FlightRoomState(room.id(), GameType.FLIGHT, room.code(), room.name(), room.hostUsername(),
                snapshot, room.status(), room.recovered());
    }
}
