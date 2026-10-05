package cn.friendpoker.server.room;

import cn.friendpoker.domain.card.Card;
import cn.friendpoker.domain.card.Deck;
import cn.friendpoker.domain.card.Rank;
import cn.friendpoker.domain.card.Suit;
import cn.friendpoker.domain.game.ActionType;
import cn.friendpoker.domain.game.HoldemTable;
import cn.friendpoker.domain.game.PlayerSnapshot;
import cn.friendpoker.domain.game.Street;
import cn.friendpoker.domain.game.TableSnapshot;
import cn.friendpoker.server.PokerApplication;
import cn.friendpoker.server.bot.BotScheduler;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import cn.friendpoker.server.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = PokerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:bot-human-timer-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class BotHumanTimeoutTest {
    @Autowired RoomService rooms;
    @Autowired RoomStore store;
    @Autowired GameCoordinator games;
    @Autowired UserAccountRepository users;
    @MockitoBean BotScheduler bots;

    @Test
    void botHandingTheTurnToAHumanInstallsTheMatchingTimerAndTimeoutOnlyActsOnce() {
        PokerRoom room = botToActOnFlop();
        try {
            String bot = room.bots().getFirst().id();
            games.botStep(room.id(), room.stateVersion(), bot, UUID.randomUUID().toString(), 0);

            TableSnapshot humanTurn = room.table().snapshotFor(room.hostUsername());
            assertEquals(room.hostUsername(), actor(humanTurn).id());
            assertNotNull(room.actionDeadline());
            Object timer = timer(room);
            assertNotNull(timer, "The deadline must have a scheduled timeout, not only a timestamp");
            assertEquals(humanTurn.version(), ReflectionTestUtils.getField(timer, "version"));
            assertEquals(room.actionDeadline(), ReflectionTestUtils.getField(timer, "deadline"));
            ScheduledFuture<?> future = (ScheduledFuture<?>) ReflectionTestUtils.getField(timer, "future");
            assertNotNull(future);
            assertFalse(future.isCancelled());

            games.timeout(room.id(), humanTurn.version());
            TableSnapshot after = room.table().snapshotFor("");
            long committedVersion = room.stateVersion();
            assertEquals(humanTurn.version() + 1, after.version());
            assertEquals(Street.TURN, after.street());
            assertEquals(bot, actor(after).id());
            assertNull(timer(room));
            assertTrue(future.isCancelled());
            games.timeout(room.id(), humanTurn.version());
            assertEquals(committedVersion, room.stateVersion());
            assertEquals(after, room.table().snapshotFor(""));
        } finally {
            games.close(room.id(), room.hostUsername());
        }
    }

    @Test
    void pausedBotHasNoHumanTimerAndCannotBePlayedByEitherBotStepOrTimeout() {
        PokerRoom room = botToActOnFlop();
        try {
            games.botSettings(room.id(), room.hostUsername(), false, null);
            long roomVersion = room.stateVersion();
            TableSnapshot before = room.table().snapshotFor("");
            assertNull(room.actionDeadline());
            assertNull(timer(room));

            games.botStep(room.id(), roomVersion, room.bots().getFirst().id(), UUID.randomUUID().toString(), 0);
            games.timeout(room.id(), before.version());
            assertEquals(roomVersion, room.stateVersion());
            assertEquals(before, room.table().snapshotFor(""));
            assertNull(timer(room));
        } finally {
            games.close(room.id(), room.hostUsername());
        }
    }

    @Test
    void pausingBotsDoesNotCancelAnAlreadyScheduledHumanTurn() {
        PokerRoom room = botToActOnFlop();
        try {
            games.botStep(room.id(), room.stateVersion(), room.bots().getFirst().id(),
                    UUID.randomUUID().toString(), 0);
            TableSnapshot humanTurn = room.table().snapshotFor(room.hostUsername());
            Object humanTimer = timer(room);
            assertNotNull(humanTimer);

            games.botSettings(room.id(), room.hostUsername(), false, null);
            assertEquals(humanTimer, timer(room));
            assertEquals(humanTurn.version(), ReflectionTestUtils.getField(humanTimer, "version"));
            games.timeout(room.id(), humanTurn.version());
            assertEquals(humanTurn.version() + 1, room.table().snapshotFor("").version());
            assertNull(room.actionDeadline());
            assertNull(timer(room));
        } finally {
            games.close(room.id(), room.hostUsername());
        }
    }

    private PokerRoom botToActOnFlop() {
        String host = "timer_" + UUID.randomUUID().toString().substring(0, 8);
        users.save(new UserAccount(host, "test-only-password-hash", "Human", UserRole.PLAYER));
        PokerRoom room = rooms.create(host, new CreateRoomRequest("机器人真人计时回归", "", 10, 20, 1_000, 2, 120));
        when(bots.hasHuman(any(UUID.class))).thenReturn(true);
        games.sit(room.id(), host, "Human", 0);
        games.addBots(room.id(), host, 1);
        String bot = room.bots().getFirst().id();

        HoldemTable candidate = HoldemTable.restore(room.config(), room.table().exportState());
        List<Card> deck = new ArrayList<>();
        for (Suit suit : Suit.values()) for (Rank rank : Rank.values()) deck.add(new Card(rank, suit));
        // Heads-up deals bot first. Give it weak unpaired cards so its free flop action is deterministically CHECK.
        place(deck, 0, new Card(Rank.TWO, Suit.SPADES));
        place(deck, 1, new Card(Rank.ACE, Suit.SPADES));
        place(deck, 2, new Card(Rank.SEVEN, Suit.HEARTS));
        place(deck, 3, new Card(Rank.ACE, Suit.HEARTS));
        place(deck, 5, new Card(Rank.THREE, Suit.DIAMONDS));
        place(deck, 6, new Card(Rank.FOUR, Suit.CLUBS));
        place(deck, 7, new Card(Rank.NINE, Suit.DIAMONDS));
        candidate.startHand(new Deck(deck));
        candidate.act(host, ActionType.CALL, 0, candidate.snapshotFor(host).version());
        candidate.act(bot, ActionType.CHECK, 0, candidate.snapshotFor(bot).version());
        assertEquals(Street.FLOP, candidate.snapshotFor(bot).street());
        assertEquals(bot, actor(candidate.snapshotFor(bot)).id());
        long version = store.saveState(room, candidate, room.recentActionIds(), null, RoomStatus.ACTIVE);
        room.install(candidate, room.recentActionIds(), null, RoomStatus.ACTIVE, version);
        return room;
    }

    private Object timer(PokerRoom room) {
        Map<?, ?> timers = (Map<?, ?>) ReflectionTestUtils.getField(games, "timers");
        assertNotNull(timers);
        return timers.get(room.id());
    }

    private static PlayerSnapshot actor(TableSnapshot snapshot) {
        return snapshot.players().stream().filter(p -> Integer.valueOf(p.seat()).equals(snapshot.actorSeat()))
                .findFirst().orElseThrow();
    }

    private static void place(List<Card> deck, int index, Card card) {
        Collections.swap(deck, index, deck.indexOf(card));
    }
}
