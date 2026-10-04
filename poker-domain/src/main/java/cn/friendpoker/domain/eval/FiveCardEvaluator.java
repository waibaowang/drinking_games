package cn.friendpoker.domain.eval;

import cn.friendpoker.domain.card.Card;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FiveCardEvaluator {
    private FiveCardEvaluator() {
    }

    public static HandValue evaluate(List<Card> cards) {
        if (cards.size() != 5 || cards.stream().distinct().count() != 5) {
            throw new IllegalArgumentException("Exactly five unique cards are required");
        }

        Map<Integer, Integer> counts = new HashMap<>();
        cards.forEach(card -> counts.merge(card.rank().value(), 1, Integer::sum));
        List<Integer> ranksDesc = cards.stream().map(card -> card.rank().value())
                .sorted(Comparator.reverseOrder()).toList();
        boolean flush = cards.stream().map(Card::suit).distinct().count() == 1;
        int straightHigh = straightHigh(counts.keySet().stream().toList());

        if (flush && straightHigh > 0) {
            return new HandValue(HandCategory.STRAIGHT_FLUSH, List.of(straightHigh));
        }

        List<Map.Entry<Integer, Integer>> groups = new ArrayList<>(counts.entrySet());
        groups.sort(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue)
                .reversed().thenComparing(Map.Entry::getKey, Comparator.reverseOrder()));

        if (groups.getFirst().getValue() == 4) {
            return new HandValue(HandCategory.FOUR_OF_A_KIND,
                    List.of(groups.getFirst().getKey(), groups.get(1).getKey()));
        }
        if (groups.getFirst().getValue() == 3 && groups.get(1).getValue() == 2) {
            return new HandValue(HandCategory.FULL_HOUSE,
                    List.of(groups.getFirst().getKey(), groups.get(1).getKey()));
        }
        if (flush) {
            return new HandValue(HandCategory.FLUSH, ranksDesc);
        }
        if (straightHigh > 0) {
            return new HandValue(HandCategory.STRAIGHT, List.of(straightHigh));
        }
        if (groups.getFirst().getValue() == 3) {
            List<Integer> values = new ArrayList<>();
            values.add(groups.getFirst().getKey());
            groups.stream().skip(1).map(Map.Entry::getKey).sorted(Comparator.reverseOrder()).forEach(values::add);
            return new HandValue(HandCategory.THREE_OF_A_KIND, values);
        }
        if (groups.getFirst().getValue() == 2 && groups.get(1).getValue() == 2) {
            int highPair = Math.max(groups.getFirst().getKey(), groups.get(1).getKey());
            int lowPair = Math.min(groups.getFirst().getKey(), groups.get(1).getKey());
            int kicker = groups.stream().filter(group -> group.getValue() == 1).findFirst().orElseThrow().getKey();
            return new HandValue(HandCategory.TWO_PAIR, List.of(highPair, lowPair, kicker));
        }
        if (groups.getFirst().getValue() == 2) {
            List<Integer> values = new ArrayList<>();
            values.add(groups.getFirst().getKey());
            groups.stream().skip(1).map(Map.Entry::getKey).sorted(Comparator.reverseOrder()).forEach(values::add);
            return new HandValue(HandCategory.ONE_PAIR, values);
        }
        return new HandValue(HandCategory.HIGH_CARD, ranksDesc);
    }

    private static int straightHigh(List<Integer> ranks) {
        List<Integer> unique = ranks.stream().distinct().sorted().toList();
        if (unique.size() != 5) {
            return 0;
        }
        if (unique.equals(List.of(2, 3, 4, 5, 14))) {
            return 5;
        }
        return unique.getLast() - unique.getFirst() == 4 ? unique.getLast() : 0;
    }
}

