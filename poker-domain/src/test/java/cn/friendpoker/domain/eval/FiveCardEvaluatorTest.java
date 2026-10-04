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

    private Card card(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }
}

