package cn.friendpoker.domain.game;

import org.junit.jupiter.api.Test;

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

    private void act(HoldemTable table, String player, ActionType action) {
        TableSnapshot snapshot = table.snapshotFor(player);
        table.act(player, action, 0, snapshot.version());
    }
}
