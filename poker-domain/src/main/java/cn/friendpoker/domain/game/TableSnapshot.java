package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;

import java.util.List;
import java.util.Set;

public record TableSnapshot(
        long version,
        long handNumber,
        Street street,
        long pot,
        long currentBet,
        long minimumRaise,
        Integer actorSeat,
        List<Card> board,
        List<PlayerSnapshot> players,
        Set<ActionType> legalActions,
        List<String> messages,
        String shuffleCommitment,
        String shuffleSeedReveal
) {
    public TableSnapshot {
        board = List.copyOf(board);
        players = List.copyOf(players);
        legalActions = Set.copyOf(legalActions);
        messages = List.copyOf(messages);
    }
}
