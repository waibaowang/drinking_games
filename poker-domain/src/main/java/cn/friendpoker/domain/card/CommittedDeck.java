package cn.friendpoker.domain.card;

public record CommittedDeck(Deck deck, String commitment, String seed) {
}

