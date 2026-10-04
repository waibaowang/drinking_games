package cn.friendpoker.domain.eval;

import cn.friendpoker.domain.card.Card;
import cn.friendpoker.domain.card.Rank;
import cn.friendpoker.domain.card.Suit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FiveCardEvaluatorTest {
    @Test
    void recognizesWheelStraightAndRoyalFlush() {
        HandValue wheel = FiveCardEvaluator.evaluate(List.of(
                card(Rank.ACE, Suit.CLUBS), card(Rank.TWO, Suit.DIAMONDS),
                card(Rank.THREE, Suit.HEARTS), card(Rank.FOUR, Suit.SPADES), card(Rank.FIVE, Suit.CLUBS)));
        HandValue royal = FiveCardEvaluator.evaluate(List.of(
                card(Rank.TEN, Suit.SPADES), card(Rank.JACK, Suit.SPADES), card(Rank.QUEEN, Suit.SPADES),
                card(Rank.KING, Suit.SPADES), card(Rank.ACE, Suit.SPADES)));

        assertEquals(HandCategory.STRAIGHT, wheel.category());
        assertEquals(List.of(5), wheel.kickers());
        assertEquals(HandCategory.STRAIGHT_FLUSH, royal.category());
        assertTrue(royal.compareTo(wheel) > 0);
    }

    @Test
    void comparesPairKickersCorrectly() {
        HandValue aceKicker = FiveCardEvaluator.evaluate(List.of(
                card(Rank.KING, Suit.CLUBS), card(Rank.KING, Suit.DIAMONDS), card(Rank.ACE, Suit.HEARTS),
                card(Rank.NINE, Suit.SPADES), card(Rank.TWO, Suit.CLUBS)));
        HandValue queenKicker = FiveCardEvaluator.evaluate(List.of(
                card(Rank.KING, Suit.HEARTS), card(Rank.KING, Suit.SPADES), card(Rank.QUEEN, Suit.CLUBS),
                card(Rank.NINE, Suit.DIAMONDS), card(Rank.TWO, Suit.HEARTS)));

        assertTrue(aceKicker.compareTo(queenKicker) > 0);
    }

    @Test
    void recognizesEveryHandCategoryInStrengthOrder() {
        List<HandValue> values = List.of(
                value("2C 5D 8H JS AC"),
                value("2C 2D 8H JS AC"),
                value("2C 2D 8H 8S AC"),
                value("2C 2D 2H JS AC"),
                value("5C 6D 7H 8S 9C"),
                value("2H 5H 8H JH AH"),
                value("2C 2D 2H 8S 8C"),
                value("2C 2D 2H 2S AC"),
                value("5H 6H 7H 8H 9H"));

        assertEquals(List.of(HandCategory.HIGH_CARD, HandCategory.ONE_PAIR, HandCategory.TWO_PAIR,
                HandCategory.THREE_OF_A_KIND, HandCategory.STRAIGHT, HandCategory.FLUSH,
                HandCategory.FULL_HOUSE, HandCategory.FOUR_OF_A_KIND, HandCategory.STRAIGHT_FLUSH),
                values.stream().map(HandValue::category).toList());
        for (int i = 1; i < values.size(); i++) assertTrue(values.get(i).compareTo(values.get(i - 1)) > 0);
    }

    @Test
    void comparesTwoPairAndFullHouseKickers() {
        assertTrue(value("AC AD KC KD 2S").compareTo(value("AC AD QC QD KS")) > 0);
        assertTrue(value("9C 9D 9S 2C 2D").compareTo(value("8C 8D 8S AC AD")) > 0);
    }

    private HandValue value(String cards) {
        return FiveCardEvaluator.evaluate(java.util.Arrays.stream(cards.split(" ")).map(this::card).toList());
    }

    private Card card(String text) {
        Rank rank = switch (text.charAt(0)) {
            case '2' -> Rank.TWO; case '3' -> Rank.THREE; case '4' -> Rank.FOUR; case '5' -> Rank.FIVE;
            case '6' -> Rank.SIX; case '7' -> Rank.SEVEN; case '8' -> Rank.EIGHT; case '9' -> Rank.NINE;
            case 'T' -> Rank.TEN; case 'J' -> Rank.JACK; case 'Q' -> Rank.QUEEN; case 'K' -> Rank.KING;
            default -> Rank.ACE;
        };
        Suit suit = switch (text.charAt(1)) { case 'C' -> Suit.CLUBS; case 'D' -> Suit.DIAMONDS;
            case 'H' -> Suit.HEARTS; default -> Suit.SPADES; };
        return card(rank, suit);
    }

    private Card card(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }
}
