package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.HoldemTable;
import cn.friendpoker.domain.game.TableConfig;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class PokerRoom {
    private final UUID id;
    private final String code;
    private final String name;
    private final String passwordHash;
    private final String hostUsername;
    private final TableConfig config;
    private final HoldemTable table;
    private final Set<String> members = new LinkedHashSet<>();
    private final Instant createdAt = Instant.now();

    PokerRoom(UUID id, String code, String name, String passwordHash, String hostUsername, TableConfig config) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.passwordHash = passwordHash;
        this.hostUsername = hostUsername;
        this.config = config;
        this.table = new HoldemTable(config);
        this.members.add(hostUsername);
    }

    public UUID id() { return id; }
    public String code() { return code; }
    public String name() { return name; }
    public String passwordHash() { return passwordHash; }
    public String hostUsername() { return hostUsername; }
    public TableConfig config() { return config; }
    public HoldemTable table() { return table; }
    public Instant createdAt() { return createdAt; }

    public synchronized void addMember(String username) { members.add(username); }
    public synchronized boolean hasMember(String username) { return members.contains(username); }
    public synchronized Set<String> members() { return Set.copyOf(members); }
}

