package cn.friendpoker.domain.flight;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightGameTest {
    @Test
    void consecutiveSixesNeverEndTheTurn() {
        FlightGame game = startedGame();

        for (int count = 0; count < 12; count++) {
            game.roll("alice", game.snapshotFor("alice").version(), 6);
            FlightSnapshot rolled = game.snapshotFor("alice");
            game.move("alice", rolled.legalPieceIndexes().getFirst(), rolled.version());
            assertEquals(0, game.snapshotFor("alice").currentSeat());
            assertEquals(FlightPhase.ROLL, game.snapshotFor("alice").phase());
            assertEquals(1, game.snapshotFor("alice").roundNumber());
        }
    }

    @Test
    void spawnsTwoItemsFromRoundTwoAndNeverHasMoreThanFour() {
        FlightGame game = startedGame();

        finishRoundWithoutMoves(game);
        assertEquals(2, game.snapshotFor("").roundNumber());
        assertEquals(2, game.snapshotFor("").boardItems().size());

        finishRoundWithoutMoves(game);
        assertEquals(3, game.snapshotFor("").roundNumber());
        assertEquals(2, game.snapshotFor("").boardItems().size());

        finishRoundWithoutMoves(game);
        assertEquals(4, game.snapshotFor("").roundNumber());
        assertEquals(4, game.snapshotFor("").boardItems().size());

        finishRoundWithoutMoves(game);
        assertEquals(5, game.snapshotFor("").roundNumber());
        assertEquals(2, game.snapshotFor("").boardItems().size());
        assertTrue(game.snapshotFor("").boardItems().stream().allMatch(item -> item.spawnRound() == 4));
    }

    @Test
    void moveCapturesAndCollectsOnlyAtTheFinalCell() {
        FlightGame game = FlightGame.restore(new FlightPersistenceState(
                List.of(player("alice", 0, 0, -1, -1, -1), player("bob", 1, 40, -1, -1, -1)),
                FlightPhase.MOVE, 8, 1, 2, 0, 1, Set.of(),
                List.of(new FlightBoardItem(1, "MYSTERY", 1, 2, 5)), 2, List.of(), null));

        game.move("alice", 0, 8);

        FlightSnapshot snapshot = game.snapshotFor("");
        assertEquals(1, snapshot.players().getFirst().pieces().getFirst());
        assertEquals(FlightGame.HANGAR, snapshot.players().get(1).pieces().getFirst());
        assertTrue(snapshot.boardItems().isEmpty());
    }

    @Test
    void stateRestoresExactlyWhileWaitingForAPieceChoice() {
        FlightGame original = startedGame();
        original.roll("alice", original.snapshotFor("alice").version(), 6);

        FlightGame restored = FlightGame.restore(original.exportState());

        assertEquals(original.exportState(), restored.exportState());
        assertEquals(original.snapshotFor("alice"), restored.snapshotFor("alice"));
    }

    private FlightGame startedGame() {
        FlightGame game = new FlightGame();
        game.sitDown("alice", "Alice", 0);
        game.sitDown("bob", "Bob", 1);
        game.start();
        return game;
    }

    private void finishRoundWithoutMoves(FlightGame game) {
        for (int seat = 0; seat < 2; seat++) {
            String player = seat == 0 ? "alice" : "bob";
            game.roll(player, game.snapshotFor(player).version(), 1);
        }
    }

    private FlightPlayerSnapshot player(String id, int seat, Integer... pieces) {
        return new FlightPlayerSnapshot(id, id, seat, List.of(pieces), seat == 0);
    }
}
