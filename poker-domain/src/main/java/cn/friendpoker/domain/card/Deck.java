package cn.friendpoker.domain.card;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

public final class Deck {
    private final List<Card> cards;
    private int cursor;

    public Deck() {
        this(new SecureRandom());
    }

    public Deck(SecureRandom random) {
        List<Card> fresh = new ArrayList<>(52);
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                fresh.add(new Card(rank, suit));
            }
        }
        Collections.shuffle(fresh, random);
        this.cards = List.copyOf(fresh);
    }

    public Deck(List<Card> orderedCards) {
        if (orderedCards.size() != 52 || orderedCards.stream().distinct().count() != 52) {
            throw new IllegalArgumentException("A deck must contain 52 unique cards");
        }
        this.cards = List.copyOf(orderedCards);
    }

    public Card draw() {
        if (cursor >= cards.size()) {
            throw new NoSuchElementException("Deck is empty");
        }
        return cards.get(cursor++);
    }

    public void burn() {
        draw();
    }

    public int remaining() {
        return cards.size() - cursor;
    }
}

