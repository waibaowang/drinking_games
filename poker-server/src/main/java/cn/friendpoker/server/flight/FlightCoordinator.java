package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightGame;
import cn.friendpoker.domain.flight.FlightSnapshot;
import cn.friendpoker.domain.flight.FlightPhase;
import cn.friendpoker.domain.flight.FlightBotPolicy;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;
import cn.friendpoker.server.bot.BotScheduler;
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
import java.util.ArrayList;
import java.util.random.RandomGenerator;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class FlightCoordinator {
    private static final Logger log = LoggerFactory.getLogger(FlightCoordinator.class);
    private final FlightRoomService rooms;
    private final SimpMessagingTemplate messaging;
    private final UserAccountRepository users;
    private final BotScheduler bots;

    public FlightCoordinator(FlightRoomService rooms, SimpMessagingTemplate messaging, UserAccountRepository users, BotScheduler bots) {
        this.rooms = rooms;
        this.messaging = messaging;
        this.users = users;
        this.bots = bots;
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
            if (!room.bots().isEmpty() && candidate.snapshotFor("").players().stream().noneMatch(p -> !room.isBot(p.id()))) {
                throw new IllegalArgumentException("含机器人的房间至少需要一名真人入座");
            }
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
        scheduleBot(room);
    }

    private List<String> activeMembers(FlightRoom room) {
        return room.members().stream().filter(member -> users.findByUsernameIgnoreCase(member)
                .map(account -> account.isEnabled()).orElse(false)).toList();
    }

    private FlightRoomState toState(FlightRoom room, String viewer) {
        synchronized (room) {
            FlightSnapshot snapshot = room.game().snapshotFor(viewer);
            return new FlightRoomState(room.id(), GameType.FLIGHT, room.code(), room.name(), room.hostUsername(),
                    snapshot, room.status(), room.recovered(), room.bots(), room.botSettings(), room.stateVersion());
        }
    }

    public void addBots(UUID id, String host, Integer seat) {
        FlightRoom room = hostRoom(id, host);
        synchronized (room) {
            FlightGame candidate = cloneGame(room);
            if (candidate.snapshotFor("").players().stream().noneMatch(p -> !room.isBot(p.id()))) {
                throw new IllegalArgumentException("请先选择座位，再添加机器人");
            }
            List<BotSeat> roster = new ArrayList<>(room.bots());
            for (int index = 0; index < 4; index++) {
                final int target = index;
                if ((seat == null || seat == index) && candidate.snapshotFor("").players().stream().noneMatch(p -> p.seat() == target)) {
                    BotSeat bot = new BotSeat("bot:" + UUID.randomUUID(), "机器人 " + (index + 1), index);
                    candidate.sitDown(bot.id(), bot.name(), index);
                    roster.add(bot);
                }
            }
            if (roster.size() == room.bots().size()) throw new IllegalArgumentException("没有可用空位");
            saveBots(room, candidate, roster, room.botSettings());
        }
        broadcast(room);
    }

    public void removeBot(UUID id, String host, String botId) {
        FlightRoom room = hostRoom(id, host);
        synchronized (room) {
            if (!room.isBot(botId)) throw new IllegalArgumentException("机器人不存在");
            FlightGame candidate = cloneGame(room);
            candidate.standUp(botId);
            saveBots(room, candidate, room.bots().stream().filter(bot -> !bot.id().equals(botId)).toList(), room.botSettings());
        }
        broadcast(room);
    }

    public void botSettings(UUID id, String host, Boolean enabled, BotSettings.Speed speed) {
        FlightRoom room = hostRoom(id, host);
        synchronized (room) {
            BotSettings next = new BotSettings(enabled == null ? room.botSettings().enabled() : enabled,
                    speed == null ? room.botSettings().speed() : speed);
            saveBots(room, cloneGame(room), room.bots(), next);
        }
        broadcast(room);
    }

    private FlightRoom hostRoom(UUID id, String host) {
        FlightRoom room = rooms.requireMember(id, host);
        if (!room.hostUsername().equals(host)) throw new IllegalArgumentException("只有房主可以管理机器人");
        return room;
    }

    private void saveBots(FlightRoom room, FlightGame candidate, List<BotSeat> roster, BotSettings settings) {
        FlightRoomService.requireActive(room);
        long version = rooms.store().saveState(room, candidate, room.recentActionIds(), RoomStatus.ACTIVE, roster, settings);
        room.install(candidate, room.recentActionIds(), RoomStatus.ACTIVE, version);
        room.installBots(roster, settings);
    }

    public void resumeBots(UUID id) { rooms.find(id).ifPresent(this::scheduleBot); }

    private void scheduleBot(FlightRoom room) {
        synchronized (room) {
            FlightSnapshot view = room.game().snapshotFor("");
            BotSeat actor = room.bots().stream().filter(bot -> Integer.valueOf(bot.seat()).equals(view.currentSeat())).findFirst().orElse(null);
            if (room.status() != RoomStatus.ACTIVE || !room.botSettings().enabled() || !bots.hasHuman(room.id())
                    || actor == null || (view.phase() != FlightPhase.ROLL && view.phase() != FlightPhase.MOVE)) {
                bots.cancel(room.id());
                return;
            }
            long version = room.stateVersion();
            String actionId = UUID.randomUUID().toString();
            bots.schedule(room.id(), version, room.botSettings().delayMillis(), () -> botStep(room.id(), version, actor.id(), actionId, 0));
        }
    }

    public void botStep(UUID id, long expectedRoomVersion, String actorId, String actionId, int attempt) {
        FlightRoom room = rooms.get(id);
        try {
            synchronized (room) {
                if (room.stateVersion() != expectedRoomVersion || room.status() != RoomStatus.ACTIVE
                        || !room.botSettings().enabled() || !bots.hasHuman(id) || !room.isBot(actorId)
                        || room.hasActionId(actionId)) return;
                FlightGame candidate = cloneGame(room);
                FlightSnapshot view = candidate.snapshotFor(actorId);
                if (view.players().stream().noneMatch(p -> p.id().equals(actorId) && Integer.valueOf(p.seat()).equals(view.currentSeat()))) return;
                String operation;
                if (view.phase() == FlightPhase.ROLL) { candidate.roll(actorId, view.version()); operation = "ROLL"; }
                else if (view.phase() == FlightPhase.MOVE) {
                    candidate.move(actorId, FlightBotPolicy.choosePiece(candidate, actorId, RandomGenerator.getDefault()), view.version());
                    operation = "MOVE";
                } else return;
                install(room, candidate, room.actionIdsWith(actionId), RoomStatus.ACTIVE);
                log.info("room={} game={} user={} actionId={} operation=BOT_FLIGHT_{} accepted", id, view.gameNumber(), actorId, actionId, operation);
            }
            broadcast(room);
        } catch (RuntimeException exception) {
            log.error("room={} user={} actionId={} operation=BOT_FLIGHT failed attempt={}", id, actorId, actionId, attempt, exception);
            synchronized (room) {
                if (attempt < 2 && room.stateVersion() == expectedRoomVersion && room.status() == RoomStatus.ACTIVE) {
                    bots.schedule(id, expectedRoomVersion, 2000, () -> botStep(id, expectedRoomVersion, actorId, actionId, attempt + 1));
                }
            }
        }
    }
}
