package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightGame;
import cn.friendpoker.server.room.RoomStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class FlightRoom {
    private static final int ACTION_ID_LIMIT = 128;

    private final UUID id;
    private final String code;
    private final String name;
    private final String passwordHash;
    private final String hostUsername;
    private final Set<String> members;
    private final LinkedHashSet<String> recentActionIds;
    private final Instant createdAt;
    private FlightGame game;
    private RoomStatus status;
    private long stateVersion;
    private final boolean recovered;

    FlightRoom(UUID id, String code, String name, String passwordHash, String hostUsername) {
        this(id, code, name, passwordHash, hostUsername, new FlightGame(),
                new LinkedHashSet<>(Set.of(hostUsername)), List.of(), Instant.now(), RoomStatus.ACTIVE, 0, false);
    }

    FlightRoom(UUID id, String code, String name, String passwordHash, String hostUsername, FlightGame game,
               Set<String> members, List<String> recentActionIds, Instant createdAt, RoomStatus status,
               long stateVersion, boolean recovered) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.passwordHash = passwordHash;
        this.hostUsername = hostUsername;
        this.game = game;
        this.members = new LinkedHashSet<>(members);
        this.recentActionIds = new LinkedHashSet<>(recentActionIds);
        this.createdAt = createdAt;
        this.status = status;
        this.stateVersion = stateVersion;
        this.recovered = recovered;
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String passwordHash() { return passwordHash; }
    public String hostUsername() { return hostUsername; }
    public FlightGame game() { return game; }
    public Instant createdAt() { return createdAt; }
    public RoomStatus status() { return status; }
    public long stateVersion() { return stateVersion; }
    public boolean recovered() { return recovered; }
    public Set<String> members() { return Set.copyOf(members); }
    public boolean hasMember(String username) { return members.contains(username); }
    public void addMember(String username) { members.add(username); }
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

    public void install(FlightGame game, List<String> actionIds, RoomStatus status, long stateVersion) {
        this.game = game;
        this.recentActionIds.clear();
        this.recentActionIds.addAll(actionIds);
        this.status = status;
        this.stateVersion = stateVersion;
    }
}
