package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.ActionType;
import cn.friendpoker.domain.game.PlayerSnapshot;
import cn.friendpoker.domain.game.Street;
import cn.friendpoker.domain.game.TableSnapshot;
import cn.friendpoker.server.PokerApplication;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import cn.friendpoker.server.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = PokerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:room-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class RoomPersistenceTest {
    @Autowired RoomService rooms;
    @Autowired RoomStore store;
    @Autowired GameCoordinator games;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;

    @Test
    void persistsRoomStateDeduplicatesActionsAndSerializesConcurrentTurns() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String host = "host_" + suffix;
        String guest = "guest_" + suffix;
        users.save(new UserAccount(host, passwords.encode("password123"), "Host", UserRole.PLAYER));
        users.save(new UserAccount(guest, passwords.encode("password123"), "Guest", UserRole.PLAYER));
        PokerRoom room = rooms.create(host, new CreateRoomRequest("恢复测试", "", 10, 20, 1_000, 6, 10));
        rooms.join(room.id(), guest, "");
        games.sit(room.id(), host, "Host", 0);
        games.sit(room.id(), guest, "Guest", 3);
        games.start(room.id(), host);

        waitUntilDealt(room);
        TableSnapshot before = room.table().snapshotFor("");
        PlayerSnapshot actor = actor(before);
        ActionType first = passive(room.table().snapshotFor(actor.id()));
        games.act(room.id(), actor.id(), "same-action", first, 0, before.version());
        long acceptedVersion = room.table().snapshotFor("").version();
        games.act(room.id(), actor.id(), "same-action", first, 0, before.version());
        assertEquals(acceptedVersion, room.table().snapshotFor("").version());

        PokerRoom restored = store.loadActive().stream().filter(candidate -> candidate.id().equals(room.id()))
                .findFirst().orElseThrow();
        assertEquals(room.table().exportState(), restored.table().exportState());
        assertTrue(restored.hasActionId("same-action"));

        TableSnapshot raceState = room.table().snapshotFor("");
        PlayerSnapshot raceActor = actor(raceState);
        ActionType raceAction = passive(room.table().snapshotFor(raceActor.id()));
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> raceAction(gate, room, raceActor, raceAction, raceState.version()));
            var two = executor.submit(() -> raceTimeout(gate, room, raceState.version()));
            gate.countDown();
            one.get(); two.get();
        }
        assertEquals(raceState.version() + 1, room.table().snapshotFor("").version());

        games.close(room.id(), host);
        assertEquals(RoomStatus.CLOSED, room.status());
        assertThrows(IllegalArgumentException.class,
                () -> games.act(room.id(), raceActor.id(), "after-close", raceAction, 0,
                        room.table().snapshotFor("").version()));
    }

    private void raceAction(CountDownLatch gate, PokerRoom room, PlayerSnapshot actor,
                            ActionType action, long version) {
        try {
            gate.await();
            games.act(room.id(), actor.id(), "race-action", action, 0, version);
        } catch (Exception ignored) {
            // The timeout may win the same-version race.
        }
    }

    private void raceTimeout(CountDownLatch gate, PokerRoom room, long version) {
        try { gate.await(); games.timeout(room.id(), version); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private void waitUntilDealt(PokerRoom room) throws InterruptedException {
        long stop = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (room.table().snapshotFor("").street() == Street.SHUFFLE_PENDING && System.nanoTime() < stop) {
            Thread.sleep(25);
        }
        assertEquals(Street.PREFLOP, room.table().snapshotFor("").street());
    }

    private PlayerSnapshot actor(TableSnapshot snapshot) {
        return snapshot.players().stream().filter(player -> player.seat() == snapshot.actorSeat())
                .findFirst().orElseThrow();
    }

    private ActionType passive(TableSnapshot view) {
        return view.legalActions().contains(ActionType.CHECK) ? ActionType.CHECK : ActionType.CALL;
    }
}
