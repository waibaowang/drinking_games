package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.ActionType;
import cn.friendpoker.domain.game.GameRuleException;
import cn.friendpoker.domain.game.HoldemTable;
import cn.friendpoker.domain.game.PlayerSnapshot;
import cn.friendpoker.domain.game.Street;
import cn.friendpoker.domain.game.TableSnapshot;
import cn.friendpoker.domain.game.PokerBotPolicy;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;
import cn.friendpoker.server.bot.BotScheduler;
import cn.friendpoker.server.user.UserAccountRepository;
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
import java.util.ArrayList;
import java.util.random.RandomGenerator;
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
    private final UserAccountRepository users;
    private final BotScheduler bots;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "poker-action-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, TimerState> timers = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> pendingDeals = ConcurrentHashMap.newKeySet();

    public GameCoordinator(RoomService rooms, SimpMessagingTemplate messaging, UserAccountRepository users, BotScheduler bots) {
        this.rooms = rooms;
        this.messaging = messaging;
        this.users = users;
        this.bots = bots;
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumeRecoveredRooms() {
        rooms.allInMemory().stream().filter(room -> room.status() == RoomStatus.ACTIVE).forEach(room -> {
            if (room.table().snapshotFor("").street() != Street.SHUFFLE_PENDING) {
                scheduleTimeout(room);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public RoomState state(UUID roomId, String viewer) {
        PokerRoom room = rooms.requireMember(roomId, viewer);
        RoomState state = toState(room, viewer);
        if (state.table().street() == Street.SHUFFLE_PENDING) {
            announceCommitment(room);
            scheduleDeal(room, state.table().version(), 500);
        }
        return state;
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
            if (!room.bots().isEmpty() && candidate.snapshotFor("").players().stream().noneMatch(p -> !room.isBot(p.id()) && p.stack() > 0)) {
                throw new IllegalArgumentException("含机器人的房间至少需要一名有筹码的真人入座");
            }
            candidate.prepareHand();
            install(room, candidate, room.recentActionIds(), null, RoomStatus.ACTIVE);
            preparedVersion = candidate.snapshotFor("").version();
            log.info("room={} hand={} user={} operation=START_COMMIT accepted", room.id(),
                    candidate.snapshotFor("").handNumber(), username);
        }
        cancelTimer(room.id());
        announceCommitment(room);
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
        scheduleTimeout(room);
        broadcast(room);
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
        activeMembers(room).forEach(member -> messaging.convertAndSendToUser(member,
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
        scheduleTimeout(room);
        broadcast(room);
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
        TableSnapshot view = candidate.snapshotFor("");
        return view.actorSeat() == null || view.players().stream().anyMatch(p -> p.seat() == view.actorSeat() && room.isBot(p.id()))
                ? null : Instant.now().plusSeconds(room.config().actionSeconds());
    }

    private void scheduleDeal(PokerRoom room, long expectedVersion, long delayMillis) {
        if (!pendingDeals.add(room.id())) return;
        scheduler.schedule(() -> {
            pendingDeals.remove(room.id());
            deal(room.id(), expectedVersion);
        }, delayMillis, TimeUnit.MILLISECONDS);
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
            scheduleTimeout(room);
            broadcast(room);
        } catch (GameRuleException exception) {
            log.info("room={} operation=DEAL skipped reason={}", roomId, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("room={} operation=DEAL failed", roomId, exception);
            PokerRoom current = rooms.get(roomId);
            if (current.status() == RoomStatus.ACTIVE
                    && current.table().snapshotFor("").version() == expectedVersion) {
                scheduleDeal(current, expectedVersion, 2_000);
            }
        }
    }

    private void scheduleTimeout(PokerRoom room) {
        cancelTimer(room.id());
        TableSnapshot snapshot = room.table().snapshotFor("");
        if (room.status() != RoomStatus.ACTIVE || snapshot.actorSeat() == null || room.actionDeadline() == null) return;
        if (snapshot.players().stream().anyMatch(p -> p.seat() == snapshot.actorSeat() && room.isBot(p.id()))) return;
        long delay = Math.max(0, Duration.between(Instant.now(), room.actionDeadline()).toMillis());
        TimerState timer = new TimerState(snapshot.version(), room.actionDeadline());
        timers.put(room.id(), timer);
        timer.future = scheduler.schedule(() -> timeout(room.id(), snapshot.version()), delay, TimeUnit.MILLISECONDS);
    }

    private void cancelTimer(UUID roomId) {
        TimerState previous = timers.remove(roomId);
        if (previous != null && previous.future != null) previous.future.cancel(false);
    }

    void timeout(UUID roomId, long expectedVersion) {
        PokerRoom room;
        try {
            room = rooms.get(roomId);
            synchronized (room) {
                if (room.status() != RoomStatus.ACTIVE) return;
                TableSnapshot snapshot = room.table().snapshotFor("");
                if (snapshot.version() != expectedVersion || snapshot.actorSeat() == null) return;
                PlayerSnapshot actor = snapshot.players().stream()
                        .filter(player -> player.seat() == snapshot.actorSeat()).findFirst().orElseThrow();
                if (room.isBot(actor.id())) return;
                ActionType fallback = room.table().snapshotFor(actor.id()).legalActions().contains(ActionType.CHECK)
                        ? ActionType.CHECK : ActionType.FOLD;
                HoldemTable candidate = cloneTable(room);
                candidate.act(actor.id(), fallback, 0, expectedVersion);
                String actionId = "timeout:" + roomId + ":" + expectedVersion;
                install(room, candidate, room.actionIdsWith(actionId), nextDeadline(room, candidate), RoomStatus.ACTIVE);
                log.info("room={} hand={} user={} actionId={} operation=TIMEOUT_{} accepted", room.id(),
                        candidate.snapshotFor("").handNumber(), actor.id(), actionId, fallback);
            }
            scheduleTimeout(room);
            broadcast(room);
        } catch (GameRuleException exception) {
            log.info("room={} operation=TIMEOUT skipped reason={}", roomId, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("room={} operation=TIMEOUT failed", roomId, exception);
            PokerRoom current = rooms.get(roomId);
            if (current.status() == RoomStatus.ACTIVE
                    && current.table().snapshotFor("").version() == expectedVersion) {
                scheduleTimeoutRetry(current, expectedVersion);
            }
        }
    }

    private void scheduleTimeoutRetry(PokerRoom room, long expectedVersion) {
        cancelTimer(room.id());
        TimerState timer = new TimerState(expectedVersion, Instant.now().plusSeconds(2));
        timers.put(room.id(), timer);
        timer.future = scheduler.schedule(() -> timeout(room.id(), expectedVersion), 2, TimeUnit.SECONDS);
    }

    private void broadcast(PokerRoom room) {
        activeMembers(room).forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/rooms/" + room.id(), toState(room, member)));
        scheduleBot(room);
    }

    private void announceCommitment(PokerRoom room) {
        TableSnapshot committed = room.table().snapshotFor("");
        activeMembers(room).forEach(member -> messaging.convertAndSendToUser(member,
                "/queue/rooms/" + room.id() + "/shuffle",
                new ShuffleCommitment(committed.handNumber(), committed.shuffleCommitment(), committed.version())));
    }

    private List<String> activeMembers(PokerRoom room) {
        return room.members().stream().filter(member -> users.findByUsernameIgnoreCase(member)
                .map(account -> account.isEnabled()).orElse(false)).toList();
    }

    private RoomState toState(PokerRoom room, String viewer) {
        synchronized (room) {
            return new RoomState(room.id(), GameType.POKER, room.code(), room.name(), room.hostUsername(), room.config(),
                    room.table().snapshotFor(viewer), room.actionDeadline(), room.status(), room.recovered(), room.bots(), room.botSettings(), room.stateVersion());
        }
    }

    public void addBots(UUID id, String host, Integer seat) {
        PokerRoom room = hostRoom(id, host);
        synchronized (room) {
            HoldemTable candidate = cloneTable(room);
            if (candidate.snapshotFor("").players().stream().noneMatch(p -> !room.isBot(p.id()) && p.stack() > 0)) {
                throw new IllegalArgumentException("请先选择座位，再添加机器人");
            }
            List<BotSeat> roster = new ArrayList<>(room.bots());
            for (int index = 0; index < room.config().maxPlayers(); index++) {
                final int target = index;
                if ((seat == null || seat == index) && candidate.snapshotFor("").players().stream().noneMatch(p -> p.seat() == target)) {
                    BotSeat bot = new BotSeat("bot:" + UUID.randomUUID(), "机器人 " + (index + 1), index);
                    candidate.sitDown(bot.id(), bot.name(), index, room.config().initialStack());
                    roster.add(bot);
                }
            }
            if (roster.size() == room.bots().size()) throw new IllegalArgumentException("没有可用空位");
            saveBots(room, candidate, roster, room.botSettings());
        }
        broadcast(room);
    }

    public void removeBot(UUID id, String host, String botId) {
        PokerRoom room = hostRoom(id, host);
        synchronized (room) {
            if (!room.isBot(botId)) throw new IllegalArgumentException("机器人不存在");
            HoldemTable candidate = cloneTable(room);
            candidate.standUp(botId);
            saveBots(room, candidate, room.bots().stream().filter(bot -> !bot.id().equals(botId)).toList(), room.botSettings());
        }
        broadcast(room);
    }

    public void botSettings(UUID id, String host, Boolean enabled, BotSettings.Speed speed) {
        PokerRoom room = hostRoom(id, host);
        synchronized (room) {
            BotSettings next = new BotSettings(enabled == null ? room.botSettings().enabled() : enabled,
                    speed == null ? room.botSettings().speed() : speed);
            saveBots(room, cloneTable(room), room.bots(), next);
        }
        broadcast(room);
    }

    private PokerRoom hostRoom(UUID id, String host) {
        PokerRoom room = rooms.requireMember(id, host);
        if (!room.hostUsername().equals(host)) throw new IllegalArgumentException("只有房主可以管理机器人");
        return room;
    }

    private void saveBots(PokerRoom room, HoldemTable candidate, List<BotSeat> roster, BotSettings settings) {
        RoomService.requireActive(room);
        long version = rooms.store().saveState(room, candidate, room.recentActionIds(), room.actionDeadline(), RoomStatus.ACTIVE, roster, settings);
        room.install(candidate, room.recentActionIds(), room.actionDeadline(), RoomStatus.ACTIVE, version);
        room.installBots(roster, settings);
    }

    public void resumeBots(UUID id) { rooms.find(id).ifPresent(this::scheduleBot); }

    private void scheduleBot(PokerRoom room) {
        synchronized (room) {
            TableSnapshot view = room.table().snapshotFor("");
            BotSeat actor = room.bots().stream().filter(bot -> Integer.valueOf(bot.seat()).equals(view.actorSeat())).findFirst().orElse(null);
            if (room.status() != RoomStatus.ACTIVE || !room.botSettings().enabled() || !bots.hasHuman(room.id())
                    || actor == null || view.street() == Street.SHUFFLE_PENDING || view.street() == Street.COMPLETE) {
                bots.cancel(room.id());
                return;
            }
            long version = room.stateVersion();
            String actionId = UUID.randomUUID().toString();
            bots.schedule(room.id(), version, room.botSettings().delayMillis(), () -> botStep(room.id(), version, actor.id(), actionId, 0));
        }
    }

    public void botStep(UUID id, long expectedRoomVersion, String actorId, String actionId, int attempt) {
        PokerRoom room = rooms.get(id);
        try {
            synchronized (room) {
                if (room.stateVersion() != expectedRoomVersion || room.status() != RoomStatus.ACTIVE
                        || !room.botSettings().enabled() || !bots.hasHuman(id) || !room.isBot(actorId)
                        || room.hasActionId(actionId)) return;
                HoldemTable candidate = cloneTable(room);
                TableSnapshot view = candidate.snapshotFor(actorId);
                if (view.players().stream().noneMatch(p -> p.id().equals(actorId) && Integer.valueOf(p.seat()).equals(view.actorSeat()))) return;
                var choice = PokerBotPolicy.choose(view, actorId, room.config().bigBlind(), RandomGenerator.getDefault());
                candidate.act(actorId, choice.action(), choice.amount(), view.version());
                install(room, candidate, room.actionIdsWith(actionId), nextDeadline(room, candidate), RoomStatus.ACTIVE);
                log.info("room={} hand={} user={} actionId={} operation=BOT_{} accepted", id, view.handNumber(), actorId, actionId, choice.action());
            }
            scheduleTimeout(room);
            broadcast(room);
        } catch (RuntimeException exception) {
            log.error("room={} user={} actionId={} operation=BOT_POKER failed attempt={}", id, actorId, actionId, attempt, exception);
            synchronized (room) {
                if (attempt < 2 && room.stateVersion() == expectedRoomVersion && room.status() == RoomStatus.ACTIVE) {
                    bots.schedule(id, expectedRoomVersion, 2000, () -> botStep(id, expectedRoomVersion, actorId, actionId, attempt + 1));
                }
            }
        }
    }

    private static final class TimerState {
        private final long version;
        private final Instant deadline;
        private volatile ScheduledFuture<?> future;

        private TimerState(long version, Instant deadline) {
            this.version = version;
            this.deadline = deadline;
        }
    }
    public record ChatMessage(String username, String displayName, String text, Instant sentAt) {}
    public record ShuffleCommitment(long handNumber, String commitment, long version) {}
}
