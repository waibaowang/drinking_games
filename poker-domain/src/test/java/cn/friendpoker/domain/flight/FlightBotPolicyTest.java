package cn.friendpoker.domain.flight;

import cn.friendpoker.domain.game.GameRuleException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightBotPolicyTest {
    private static final String BOT = "bot:flight";
    private static final String OPPONENT = "alice";

    @Test
    void reachingTheFinishBeatsCapturingTwoPlanes() {
        FlightGame game = movingGame(2, List.of(55, 9, -1, 57), List.of(50, 50, -1, -1));

        assertEquals(0, choose(game));
    }

    @Test
    void prefersCapturingMorePlanes() {
        FlightGame game = movingGame(1, List.of(0, 13, -1, 57), List.of(40, 40, 1, -1));

        assertEquals(0, choose(game));
    }

    @Test
    void capturingBeatsTakingOff() {
        FlightGame game = movingGame(5, List.of(2, -1, 57, 57), List.of(46, -1, -1, -1));

        assertEquals(0, choose(game));
    }

    @Test
    void takingOffBeatsAnOrdinaryAdvance() {
        FlightGame game = movingGame(5, List.of(2, -1, 57, 57), List.of(-1, -1, -1, -1));

        assertEquals(1, choose(game));
    }

    @Test
    void usesTheOriginalJumpAndBounceRulesWhenComparingAdvances() {
        FlightGame jump = movingGame(5, List.of(13, 1, 57, 57), List.of(-1, -1, -1, -1));
        FlightGame bounce = movingGame(4, List.of(56, 50, 57, 57), List.of(-1, -1, -1, -1));

        assertEquals(0, choose(jump));
        assertEquals(1, choose(bounce));
    }

    @Test
    void finishedPlanesAreNeverChosenAndEvaluationDoesNotMutateTheGame() {
        FlightGame game = movingGame(1, List.of(57, 57, 0, 57), List.of(-1, -1, -1, -1));
        FlightPersistenceState saved = game.exportState();

        assertEquals(2, choose(game));
        assertEquals(saved, game.exportState());
    }

    @Test
    void theLastPlaneCanWinOnlyAfterTheCoordinatorCommitsTheMove() {
        FlightGame game = movingGame(2, List.of(57, 55, 57, 57), List.of(-1, -1, -1, -1));
        FlightSnapshot before = game.snapshotFor(BOT);
        int piece = choose(game);

        assertEquals(1, piece);
        assertEquals(FlightPhase.MOVE, game.snapshotFor(BOT).phase());
        game.move(BOT, piece, before.version());
        assertEquals(FlightPhase.COMPLETE, game.snapshotFor(BOT).phase());
        assertEquals(BOT, game.snapshotFor(BOT).winnerId());
    }

    @Test
    void rejectsWrongPhasesAndPlayersOutsideTheirTurn() {
        FlightGame waiting = new FlightGame();
        waiting.sitDown(BOT, "Bot", 0);
        waiting.sitDown(OPPONENT, "Alice", 1);
        assertThrows(GameRuleException.class, () -> choose(waiting));
        waiting.start();
        assertThrows(GameRuleException.class, () -> choose(waiting));

        FlightGame moving = movingGame(5, List.of(-1, -1, -1, -1), List.of(-1, -1, -1, -1));
        assertThrows(GameRuleException.class, () -> FlightBotPolicy.choosePiece(moving, OPPONENT, new Random(1)));
        assertThrows(GameRuleException.class, () -> FlightBotPolicy.choosePiece(moving, "unknown", new Random(1)));
        assertThrows(GameRuleException.class, () -> FlightBotPolicy.choosePiece(moving, "", new Random(1)));
        moving.move(BOT, 0, moving.snapshotFor(BOT).version());
        assertThrows(GameRuleException.class, () -> choose(moving));
    }

    @Test
    void tiedMovesAreRandomButRepeatableWithTheSameRandomSource() {
        FlightGame game = movingGame(6, List.of(-1, -1, -1, -1), List.of(-1, -1, -1, -1));
        Set<Integer> choices = new HashSet<>();
        Random firstSource = new Random(42);
        Random secondSource = new Random(42);
        for (int count = 0; count < 100; count++) {
            int first = FlightBotPolicy.choosePiece(game, BOT, firstSource);
            int second = FlightBotPolicy.choosePiece(game, BOT, secondSource);
            assertEquals(first, second);
            assertTrue(game.snapshotFor(BOT).legalPieceIndexes().contains(first));
            choices.add(first);
        }
        assertEquals(Set.of(0, 1, 2, 3), choices);
    }

    @Test
    void doesNotAdvanceTheRealDiceSourceOrInspectNewlySpawnedItems() {
        FlightGame original = seededGame(42);
        FlightGame control = seededGame(42);
        original.roll(BOT, original.snapshotFor(BOT).version(), 6);
        control.roll(BOT, control.snapshotFor(BOT).version(), 6);
        int piece = choose(original);
        original.move(BOT, piece, original.snapshotFor(BOT).version());
        control.move(BOT, piece, control.snapshotFor(BOT).version());
        original.roll(BOT, original.snapshotFor(BOT).version());
        control.roll(BOT, control.snapshotFor(BOT).version());
        assertEquals(control.snapshotFor(BOT), original.snapshotFor(BOT));

        FlightGame endOfRound = FlightGame.restore(new FlightPersistenceState(
                List.of(player(OPPONENT, 0, List.of(-1, -1, -1, -1)),
                        player(BOT, 1, List.of(0, 2, 57, 57))),
                FlightPhase.MOVE, 8, 1, 1, 1, 1, Set.of(0), List.of(), 1, List.of(), null));
        FlightPersistenceState before = endOfRound.exportState();
        int expected = choose(endOfRound);
        for (int count = 0; count < 20; count++) assertEquals(expected, choose(endOfRound));
        assertEquals(before, endOfRound.exportState());
        assertTrue(endOfRound.snapshotFor(BOT).boardItems().isEmpty());
    }

    @Test
    void randomizedLegalGamesKeepAdvancingUntilSomeoneWins() {
        for (int seed = 0; seed < 20; seed++) {
            FlightGame game = new FlightGame(new Random(seed));
            for (int seat = 0; seat < 4; seat++) game.sitDown("bot:" + seat, "Bot " + seat, seat);
            game.start();
            Random dice = new Random(seed * 31L + 7);
            Random choices = new Random(seed * 17L + 11);
            int operations = 0;
            while (game.snapshotFor("").phase() != FlightPhase.COMPLETE && operations++ < 10_000) {
                FlightSnapshot before = game.snapshotFor("");
                String actor = before.players().stream().filter(player -> player.seat() == before.currentSeat())
                        .findFirst().orElseThrow().id();
                if (before.phase() == FlightPhase.ROLL) {
                    game.roll(actor, before.version(), dice.nextInt(1, 7));
                } else {
                    int piece = FlightBotPolicy.choosePiece(game, actor, choices);
                    assertTrue(game.snapshotFor(actor).legalPieceIndexes().contains(piece));
                    game.move(actor, piece, before.version());
                }
                FlightSnapshot after = game.snapshotFor("");
                assertTrue(after.version() > before.version());
                assertTrue(after.players().stream().allMatch(player -> player.pieces().size() == 4
                        && player.pieces().stream().allMatch(progress -> progress >= -1 && progress <= 57)));
                assertTrue(after.boardItems().size() <= 4);
                assertEquals(after.boardItems().size(), after.boardItems().stream()
                        .map(FlightBoardItem::cellIndex).distinct().count());
            }
            assertEquals(FlightPhase.COMPLETE, game.snapshotFor("").phase(), "Seed " + seed);
        }
    }

    private static int choose(FlightGame game) {
        return FlightBotPolicy.choosePiece(game, BOT, new Random(1));
    }

    private static FlightGame seededGame(long seed) {
        FlightGame game = new FlightGame(new Random(seed));
        game.sitDown(BOT, "Bot", 0);
        game.sitDown(OPPONENT, "Alice", 1);
        game.start();
        return game;
    }

    private static FlightGame movingGame(int die, List<Integer> botPieces, List<Integer> opponentPieces) {
        return FlightGame.restore(new FlightPersistenceState(
                List.of(player(BOT, 0, botPieces), player(OPPONENT, 1, opponentPieces)),
                FlightPhase.MOVE, 8, 1, 1, 0, die, Set.of(), List.of(), 1, List.of(), null));
    }

    private static FlightPlayerSnapshot player(String id, int seat, List<Integer> pieces) {
        return new FlightPlayerSnapshot(id, id, seat, pieces, false);
    }
}
