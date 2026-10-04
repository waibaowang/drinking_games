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

    private void act(HoldemTable table, String player, ActionType action) {
        TableSnapshot snapshot = table.snapshotFor(player);
        table.act(player, action, 0, snapshot.version());
    }
}
