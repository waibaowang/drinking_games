package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;
import cn.friendpoker.domain.card.Deck;
import cn.friendpoker.domain.card.Rank;
import cn.friendpoker.domain.card.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldemTableTest {
    @Test
    void playsACompleteHeadsUpCheckDownHand() {
        HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 9, 30));
        table.sitDown("alice", "Alice", 0, 1_000);
        table.sitDown("bob", "Bob", 4, 1_000);
        table.startHand();

        act(table, "alice", ActionType.CALL);
        act(table, "bob", ActionType.CHECK);
        for (int street = 0; street < 3; street++) {
            act(table, "bob", ActionType.CHECK);
            act(table, "alice", ActionType.CHECK);
        }

        TableSnapshot snapshot = table.snapshotFor("alice");
        assertEquals(Street.COMPLETE, snapshot.street());
        assertEquals(5, snapshot.board().size());
        assertEquals(2_000, snapshot.players().stream().mapToLong(PlayerSnapshot::stack).sum());
        assertTrue(snapshot.players().stream().allMatch(player -> player.cards().size() == 2));
    }

    @Test
    void awardsThePotWhenEveryoneElseFolds() {
        HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 6, 30));
        table.sitDown("alice", "Alice", 0, 1_000);
        table.sitDown("bob", "Bob", 1, 1_000);
        table.startHand();

        act(table, "alice", ActionType.FOLD);

        TableSnapshot snapshot = table.snapshotFor("bob");
        assertEquals(Street.COMPLETE, snapshot.street());
        assertEquals(1_010, snapshot.players().stream().filter(player -> player.id().equals("bob")).findFirst().orElseThrow().stack());
        assertEquals(snapshot.shuffleCommitment(), cn.friendpoker.domain.card.FairShuffle.commitmentOf(snapshot.shuffleSeedReveal()));
    }

    @Test
    void shortBigBlindStillRequiresAFullBigBlindBringIn() {
        HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 6, 30));
        table.sitDown("alice", "Alice", 0, 1_000);
        table.sitDown("bob", "Bob", 1, 1_000);
        table.sitDown("short", "Short", 2, 15);
        table.startHand();

        TableSnapshot before = table.snapshotFor("alice");
        assertEquals(20, before.currentBet());
        table.act("alice", ActionType.CALL, 0, before.version());

        assertEquals(20, table.snapshotFor("alice").players().stream()
                .filter(player -> player.id().equals("alice")).findFirst().orElseThrow().committedStreet());
    }

    @Test
    void shuffleCommitmentIsVisibleBeforeAnyHoleCards() {
        HoldemTable table = tableWithPlayers(2);
        table.prepareHand();

        TableSnapshot committed = table.snapshotFor("p0");
        assertEquals(Street.SHUFFLE_PENDING, committed.street());
        assertTrue(committed.shuffleCommitment() != null && !committed.shuffleCommitment().isBlank());
        assertTrue(committed.players().stream().allMatch(player -> player.cards().isEmpty()));
        assertEquals(null, committed.shuffleSeedReveal());

        table.dealPreparedHand();
        assertEquals(2, table.snapshotFor("p0").players().getFirst().cards().size());
    }

    @Test
    void exportedStateRestoresExactlyAndCanFinishTheSameWay() {
        HoldemTable original = tableWithPlayers(3);
        original.startHand();
        actCurrent(original, ActionType.CALL);
        HoldemTable restored = HoldemTable.restore(new TableConfig(10, 20, 1_000, 9, 30), original.exportState());
        assertEquals(original.exportState(), restored.exportState());

        while (original.snapshotFor("").street() != Street.COMPLETE) {
            ActionType action = nextPassiveAction(original);
            actCurrent(original, action);
            actCurrent(restored, action);
            assertEquals(original.exportState(), restored.exportState());
        }
    }

    @Test
    void randomLegalPlayPreservesChipsAndAlwaysTerminates() {
        java.util.Random random = new java.util.Random(42);
        for (int game = 0; game < 30; game++) {
            HoldemTable table = tableWithPlayers(4);
            table.startHand();
            int actions = 0;
            while (table.snapshotFor("").street() != Street.COMPLETE && actions++ < 200) {
                TableSnapshot publicView = table.snapshotFor("");
                PlayerSnapshot actor = publicView.players().stream()
                        .filter(player -> player.seat() == publicView.actorSeat()).findFirst().orElseThrow();
                TableSnapshot view = table.snapshotFor(actor.id());
                java.util.List<ActionType> legal = view.legalActions().stream().toList();
                ActionType action = legal.get(random.nextInt(legal.size()));
                long amount = 0;
                if (action == ActionType.BET || action == ActionType.RAISE) {
                    long max = actor.committedStreet() + actor.stack();
                    amount = Math.min(max, publicView.currentBet() == 0 ? publicView.minimumRaise()
                            : publicView.currentBet() + publicView.minimumRaise());
                }
                table.act(actor.id(), action, amount, view.version());
                assertEquals(4_000, table.snapshotFor("").players().stream()
                        .mapToLong(player -> player.stack() + player.committedHand()).sum());
            }
            assertTrue(actions < 200);
        }
    }

    @Test
    void cumulativeShortAllInsReopenRaising() {
        HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 6, 30));
        table.sitDown("button", "Button", 0, 1_000);
        table.sitDown("short1", "Short 1", 1, 150);
        table.sitDown("short2", "Short 2", 2, 200);
        table.sitDown("raiser", "Raiser", 3, 1_000);
        table.startHand();

        actTo(table, "raiser", ActionType.RAISE, 100);
        actTo(table, "button", ActionType.CALL, 0);
        actTo(table, "short1", ActionType.ALL_IN, 0);
        actTo(table, "short2", ActionType.ALL_IN, 0);
        actTo(table, "raiser", ActionType.CALL, 0);

        assertTrue(table.snapshotFor("button").legalActions().contains(ActionType.RAISE));
    }

    @Test
    void oddChipStartsWithFirstWinnerLeftOfButton() {
        HoldemTable table = new HoldemTable(new TableConfig(1, 2, 100, 6, 30));
        table.sitDown("button", "Button", 0, 100);
        table.sitDown("small", "Small", 1, 100);
        table.sitDown("big", "Big", 2, 100);
        table.startHand(royalBoardDeck());

        actTo(table, "button", ActionType.CALL, 0);
        actTo(table, "small", ActionType.FOLD, 0);
        actTo(table, "big", ActionType.CHECK, 0);
        while (table.snapshotFor("").street() != Street.COMPLETE) actCurrent(table, ActionType.CHECK);

        TableSnapshot result = table.snapshotFor("");
        assertEquals(100, result.players().stream().filter(p -> p.id().equals("button")).findFirst().orElseThrow().stack());
        assertEquals(101, result.players().stream().filter(p -> p.id().equals("big")).findFirst().orElseThrow().stack());
    }

    private Deck royalBoardDeck() {
        List<Card> cards = new ArrayList<>();
        for (Suit suit : Suit.values()) for (Rank rank : Rank.values()) cards.add(new Card(rank, suit));
        place(cards, 7, new Card(Rank.TEN, Suit.SPADES));
        place(cards, 8, new Card(Rank.JACK, Suit.SPADES));
        place(cards, 9, new Card(Rank.QUEEN, Suit.SPADES));
        place(cards, 11, new Card(Rank.KING, Suit.SPADES));
        place(cards, 13, new Card(Rank.ACE, Suit.SPADES));
        return new Deck(cards);
    }

    private void place(List<Card> cards, int index, Card card) {
        int current = cards.indexOf(card);
        Card displaced = cards.get(index);
        cards.set(index, card);
        cards.set(current, displaced);
    }

    private HoldemTable tableWithPlayers(int count) {
        HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 9, 30));
        for (int i = 0; i < count; i++) table.sitDown("p" + i, "P" + i, i * 2, 1_000);
        return table;
    }

    private ActionType nextPassiveAction(HoldemTable table) {
        TableSnapshot snapshot = table.snapshotFor("");
        PlayerSnapshot actor = snapshot.players().stream().filter(p -> p.seat() == snapshot.actorSeat()).findFirst().orElseThrow();
        return table.snapshotFor(actor.id()).legalActions().contains(ActionType.CHECK) ? ActionType.CHECK : ActionType.CALL;
    }

    private void actCurrent(HoldemTable table, ActionType action) {
        TableSnapshot snapshot = table.snapshotFor("");
        PlayerSnapshot actor = snapshot.players().stream().filter(p -> p.seat() == snapshot.actorSeat()).findFirst().orElseThrow();
        table.act(actor.id(), action, 0, snapshot.version());
    }

    private void actTo(HoldemTable table, String player, ActionType action, long amount) {
        TableSnapshot snapshot = table.snapshotFor(player);
        table.act(player, action, amount, snapshot.version());
    }

    private void act(HoldemTable table, String player, ActionType action) {
        TableSnapshot snapshot = table.snapshotFor(player);
        table.act(player, action, 0, snapshot.version());
    }
}
