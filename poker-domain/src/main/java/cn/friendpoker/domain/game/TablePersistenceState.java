package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record TablePersistenceState(
        List<PlayerPersistenceState> players,
        List<Card> board,
        List<Card> deck,
        int deckCursor,
        Street street,
        long version,
        long handNumber,
        long currentBet,
        long minimumRaise,
        int buttonSeat,
        int smallBlindSeat,
        int bigBlindSeat,
        Integer actorSeat,
        Set<Integer> needsAction,
        Set<Integer> actedSinceFullRaise,
        Map<Integer, Long> actedAtBet,
        List<String> messages,
        String shuffleCommitment,
        String shuffleSeed
) {
    public TablePersistenceState {
        players = List.copyOf(players);
        board = List.copyOf(board);
        deck = deck == null ? List.of() : List.copyOf(deck);
        needsAction = needsAction == null ? Set.of() : Set.copyOf(needsAction);
        actedSinceFullRaise = actedSinceFullRaise == null ? Set.of() : Set.copyOf(actedSinceFullRaise);
        actedAtBet = actedAtBet == null ? Map.of() : Map.copyOf(actedAtBet);
        messages = List.copyOf(messages);
    }
}
