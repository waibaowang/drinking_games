package cn.friendpoker.server.flight;

import cn.friendpoker.domain.flight.FlightPersistenceState;
import cn.friendpoker.server.PokerApplication;
import cn.friendpoker.server.room.RoomStatus;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import cn.friendpoker.server.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = PokerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:flight-room-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class FlightRoomPersistenceTest {
    @Autowired FlightRoomService rooms;
    @Autowired FlightRoomStore store;
    @Autowired FlightCoordinator games;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void persistsFlightStateAndDeduplicatesActions() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String host = "flight_host_" + suffix;
        String guest = "flight_guest_" + suffix;
        users.save(new UserAccount(host, passwords.encode("password123"), "Host", UserRole.PLAYER));
        users.save(new UserAccount(guest, passwords.encode("password123"), "Guest", UserRole.PLAYER));

        FlightRoom room = rooms.create(host, new CreateFlightRoomRequest("飞行棋恢复测试", ""));
        rooms.join(room.id(), guest, "");
        games.sit(room.id(), host, "Host", 0);
        games.sit(room.id(), guest, "Guest", 1);
        games.start(room.id(), host);

        long version = room.game().snapshotFor(host).version();
        games.act(room.id(), host, "same-flight-action", "ROLL", null, version);
        long acceptedVersion = room.game().snapshotFor(host).version();
        games.act(room.id(), host, "same-flight-action", "ROLL", null, version);
        assertEquals(acceptedVersion, room.game().snapshotFor(host).version());

        FlightRoom restored = store.loadActive().stream().filter(candidate -> candidate.id().equals(room.id()))
                .findFirst().orElseThrow();
        FlightPersistenceState saved = room.game().exportState();
        assertEquals(saved, restored.game().exportState());
        assertTrue(restored.hasActionId("same-flight-action"));
        assertTrue(restored.recovered());

        games.close(room.id(), host);
        assertEquals(RoomStatus.CLOSED, room.status());
        assertThrows(IllegalArgumentException.class,
                () -> games.act(room.id(), host, "after-close", "ROLL", null,
                        room.game().snapshotFor(host).version()));
    }
}
