package cn.friendpoker.domain.flight;

import cn.friendpoker.domain.game.GameRuleException;

import java.util.Objects;
import java.util.random.RandomGenerator;

public final class FlightBotPolicy {
    private static final int FINISH_SCORE = 1_000_000;
    private static final int CAPTURE_SCORE = 10_000;
    private static final int TAKEOFF_SCORE = 1_000;

    private FlightBotPolicy() {
    }

    public static int choosePiece(FlightGame game, String botId, RandomGenerator random) {
        Objects.requireNonNull(game);
        Objects.requireNonNull(botId);
        Objects.requireNonNull(random);
        FlightSnapshot before;
        FlightPersistenceState saved;
        synchronized (game) {
            before = game.snapshotFor(botId);
            if (before.phase() != FlightPhase.MOVE || before.legalPieceIndexes().isEmpty()) {
                throw new GameRuleException("当前没有机器人可以移动的飞机");
            }
            saved = game.exportState();
        }

        FlightPlayerSnapshot player = player(before, botId);
        int opponentHangars = opponentHangars(before, botId);
        int bestPiece = -1;
        int bestScore = Integer.MIN_VALUE;
        int tied = 0;
        for (int pieceIndex : before.legalPieceIndexes()) {
            FlightGame candidate = FlightGame.restore(saved);
            candidate.move(botId, pieceIndex, before.version());
            FlightSnapshot after = candidate.snapshotFor(botId);
            int origin = player.pieces().get(pieceIndex);
            int destination = player(after, botId).pieces().get(pieceIndex);
            int captures = opponentHangars(after, botId) - opponentHangars;
            int score = (destination == FlightGame.FINISHED ? FINISH_SCORE : 0)
                    + captures * CAPTURE_SCORE
                    + (origin == FlightGame.HANGAR ? TAKEOFF_SCORE : 0)
                    + destination - origin;
            if (score > bestScore) {
                bestScore = score;
                bestPiece = pieceIndex;
                tied = 1;
            } else if (score == bestScore && random.nextInt(++tied) == 0) {
                bestPiece = pieceIndex;
            }
        }
        return bestPiece;
    }

    private static FlightPlayerSnapshot player(FlightSnapshot snapshot, String id) {
        return snapshot.players().stream().filter(player -> player.id().equals(id)).findFirst().orElseThrow();
    }

    private static int opponentHangars(FlightSnapshot snapshot, String botId) {
        return (int) snapshot.players().stream().filter(player -> !player.id().equals(botId))
                .flatMap(player -> player.pieces().stream()).filter(progress -> progress == FlightGame.HANGAR)
                .count();
    }
}
