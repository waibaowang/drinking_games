package cn.friendpoker.server.flight;

import cn.friendpoker.server.room.GameType;
import cn.friendpoker.server.room.RoomStatus;
import cn.friendpoker.server.room.RoomStore;
import cn.friendpoker.server.room.RoomSummary;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FlightRoomService {
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final Map<UUID, FlightRoom> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder passwordEncoder;
    private final FlightRoomStore store;
    private final RoomStore pokerStore;

    public FlightRoomService(PasswordEncoder passwordEncoder, FlightRoomStore store, RoomStore pokerStore) {
        this.passwordEncoder = passwordEncoder;
        this.store = store;
        this.pokerStore = pokerStore;
    }

    @PostConstruct
    void restore() {
        store.loadActive().forEach(room -> rooms.put(room.id(), room));
    }

    public FlightRoom create(String hostUsername, CreateFlightRoomRequest request) {
        String passwordHash = request.password().isBlank() ? null : passwordEncoder.encode(request.password());
        FlightRoom room = new FlightRoom(UUID.randomUUID(), uniqueCode(), request.name().trim(), passwordHash,
                hostUsername);
        store.insert(room);
        rooms.put(room.id(), room);
        return room;
    }

    public FlightRoom join(UUID roomId, String username, String password) {
        FlightRoom room = get(roomId);
        synchronized (room) {
            requireActive(room);
            if (room.passwordHash() != null
                    && !passwordEncoder.matches(password == null ? "" : password, room.passwordHash())) {
                throw new IllegalArgumentException("房间密码错误");
            }
            if (!room.hasMember(username)) {
                store.addMember(roomId, username);
                room.addMember(username);
            }
        }
        return room;
    }

    public Optional<FlightRoom> find(UUID id) { return Optional.ofNullable(rooms.get(id)); }

    public Optional<FlightRoom> findByCode(String code) {
        String cleaned = code == null ? "" : code.trim();
        return rooms.values().stream().filter(room -> room.status() == RoomStatus.ACTIVE)
                .filter(room -> room.code().equalsIgnoreCase(cleaned)).findFirst();
    }

    public FlightRoom get(UUID id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("飞行棋房间不存在"));
    }

    public FlightRoom requireMember(UUID id, String username) {
        FlightRoom room = get(id);
        if (!room.hasMember(username)) throw new IllegalArgumentException("你还没有加入这个房间");
        return room;
    }

    public List<RoomSummary> mine(String username) {
        return rooms.values().stream().filter(room -> room.status() == RoomStatus.ACTIVE && room.hasMember(username))
                .sorted(Comparator.comparing(FlightRoom::createdAt).reversed()).map(this::summary).toList();
    }

    public List<RoomSummary> activeRooms() {
        return rooms.values().stream().filter(room -> room.status() == RoomStatus.ACTIVE)
                .sorted(Comparator.comparing(FlightRoom::createdAt).reversed()).map(this::summary).toList();
    }

    public FlightRoomStore store() { return store; }

    private RoomSummary summary(FlightRoom room) {
        return new RoomSummary(room.id(), GameType.FLIGHT, room.code(), room.name(), room.passwordHash() != null,
                room.members().size() + room.bots().size(), 4, 0, 0, room.status(), room.recovered());
    }

    static void requireActive(FlightRoom room) {
        if (room.status() == RoomStatus.CLOSED) throw new IllegalArgumentException("房间已关闭");
    }

    private String uniqueCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            code = builder.toString().toUpperCase(Locale.ROOT);
        } while (store.codeExists(code) || pokerStore.codeExists(code));
        return code;
    }
}
