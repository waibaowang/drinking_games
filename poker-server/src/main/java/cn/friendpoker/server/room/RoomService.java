package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.TableConfig;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final Map<UUID, PokerRoom> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder passwordEncoder;

    public RoomService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public PokerRoom create(String hostUsername, CreateRoomRequest request) {
        TableConfig config = new TableConfig(request.smallBlind(), request.bigBlind(), request.initialStack(),
                request.maxPlayers(), request.actionSeconds());
        String passwordHash = request.password() == null || request.password().isBlank()
                ? null : passwordEncoder.encode(request.password());
        PokerRoom room = new PokerRoom(UUID.randomUUID(), uniqueCode(), request.name().trim(), passwordHash, hostUsername, config);
        rooms.put(room.id(), room);
        return room;
    }

    public PokerRoom join(UUID roomId, String username, String password) {
        PokerRoom room = get(roomId);
        if (room.passwordHash() != null && !passwordEncoder.matches(password == null ? "" : password, room.passwordHash())) {
            throw new IllegalArgumentException("房间密码错误");
        }
        room.addMember(username);
        return room;
    }

    public PokerRoom joinByCode(String code, String username, String password) {
        PokerRoom room = rooms.values().stream().filter(candidate -> candidate.code().equalsIgnoreCase(code.trim()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("没有找到这个房间"));
        return join(room.id(), username, password);
    }

    public PokerRoom get(UUID id) {
        PokerRoom room = rooms.get(id);
        if (room == null) {
            throw new IllegalArgumentException("房间不存在或服务器已经重启");
        }
        return room;
    }

    public PokerRoom requireMember(UUID id, String username) {
        PokerRoom room = get(id);
        if (!room.hasMember(username)) {
            throw new IllegalArgumentException("你还没有加入这个房间");
        }
        return room;
    }

    public List<RoomSummary> list() {
        return rooms.values().stream().sorted(Comparator.comparing(PokerRoom::createdAt).reversed())
                .map(room -> new RoomSummary(room.id(), room.code(), room.name(), room.passwordHash() != null,
                        room.members().size(), room.config().maxPlayers(), room.config().smallBlind(), room.config().bigBlind()))
                .toList();
    }

    private String uniqueCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                builder.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
            }
            code = builder.toString().toUpperCase(Locale.ROOT);
        } while (codeExists(code));
        return code;
    }

    private boolean codeExists(String code) {
        return rooms.values().stream().anyMatch(room -> room.code().equals(code));
    }
}

