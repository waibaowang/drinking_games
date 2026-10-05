package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.HoldemTable;
import cn.friendpoker.domain.game.TableConfig;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PokerRoom {
    private static final int ACTION_ID_LIMIT = 128;

    private final UUID id;
    private final String code;
    private final String name;
    private final String passwordHash;
    private final String hostUsername;
    private final TableConfig config;
    private final Set<String> members;
    private final LinkedHashSet<String> recentActionIds;
    private final Instant createdAt;
    private HoldemTable table;
    private RoomStatus status;
    private Instant actionDeadline;
    private long stateVersion;
    private final boolean recovered;
    private List<BotSeat> bots = List.of();
    private BotSettings botSettings = BotSettings.DEFAULT;

    PokerRoom(UUID id, String code, String name, String passwordHash, String hostUsername, TableConfig config) {
        this(id, code, name, passwordHash, hostUsername, config, new HoldemTable(config),
                new LinkedHashSet<>(Set.of(hostUsername)), List.of(), Instant.now(),
                RoomStatus.ACTIVE, null, 0, false);
    }

    PokerRoom(UUID id, String code, String name, String passwordHash, String hostUsername, TableConfig config,
              HoldemTable table, Set<String> members, List<String> recentActionIds, Instant createdAt,
              RoomStatus status, Instant actionDeadline, long stateVersion, boolean recovered) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.passwordHash = passwordHash;
        this.hostUsername = hostUsername;
        this.config = config;
        this.table = table;
        this.members = new LinkedHashSet<>(members);
        this.recentActionIds = new LinkedHashSet<>(recentActionIds);
        this.createdAt = createdAt;
        this.status = status;
        this.actionDeadline = actionDeadline;
        this.stateVersion = stateVersion;
        this.recovered = recovered;
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String passwordHash() { return passwordHash; }
    public String hostUsername() { return hostUsername; }
    public TableConfig config() { return config; }
    public HoldemTable table() { return table; }
    public Instant createdAt() { return createdAt; }
    public RoomStatus status() { return status; }
    public boolean recovered() { return recovered; }
    public Instant actionDeadline() { return actionDeadline; }
    public long stateVersion() { return stateVersion; }
    public List<BotSeat> bots() { return bots; }
    public BotSettings botSettings() { return botSettings; }
    public boolean isBot(String id) { return bots.stream().anyMatch(bot -> bot.id().equals(id)); }
    public void installBots(List<BotSeat> bots, BotSettings settings) {
        this.bots = List.copyOf(bots);
        this.botSettings = settings;
    }

    public void addMember(String username) { members.add(username); }
    public boolean hasMember(String username) { return members.contains(username); }
    public Set<String> members() { return Set.copyOf(members); }
    public boolean hasActionId(String actionId) { return recentActionIds.contains(actionId); }
    public List<String> recentActionIds() { return new ArrayList<>(recentActionIds); }

    public List<String> actionIdsWith(String actionId) {
        LinkedHashSet<String> next = new LinkedHashSet<>(recentActionIds);
        if (actionId != null) {
            next.add(actionId);
            while (next.size() > ACTION_ID_LIMIT) next.remove(next.iterator().next());
        }
        return new ArrayList<>(next);
    }

    public void install(HoldemTable table, List<String> actionIds, Instant deadline, RoomStatus status,
                        long stateVersion) {
        this.table = table;
        this.recentActionIds.clear();
        this.recentActionIds.addAll(actionIds);
        this.actionDeadline = deadline;
        this.status = status;
        this.stateVersion = stateVersion;
    }
}
