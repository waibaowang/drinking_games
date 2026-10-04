package cn.friendpoker.domain.eval;

import cn.friendpoker.domain.card.Card;

import java.util.ArrayList;
import java.util.List;

public final class SevenCardEvaluator {
    private SevenCardEvaluator() {
    }

    public static HandValue evaluate(List<Card> cards) {
        if (cards.size() != 7 || cards.stream().distinct().count() != 7) {
            throw new IllegalArgumentException("Exactly seven unique cards are required");
        }
        HandValue best = null;
        for (int a = 0; a < 3; a++) {
            for (int b = a + 1; b < 4; b++) {
                for (int c = b + 1; c < 5; c++) {
                    for (int d = c + 1; d < 6; d++) {
                        for (int e = d + 1; e < 7; e++) {
                            List<Card> five = new ArrayList<>(5);
                            five.add(cards.get(a));
                            five.add(cards.get(b));
                            five.add(cards.get(c));
                            five.add(cards.get(d));
                            five.add(cards.get(e));
                            HandValue current = FiveCardEvaluator.evaluate(five);
                            if (best == null || current.compareTo(best) > 0) {
                                best = current;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}
