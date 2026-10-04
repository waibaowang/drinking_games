package cn.friendpoker.domain.card;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FairShuffleTest {
    @Test
    void revealedSeedMatchesCommitment() {
        CommittedDeck committed = FairShuffle.create();
        assertEquals(committed.commitment(), FairShuffle.commitmentOf(committed.seed()));
        assertEquals(52, committed.deck().remaining());
    }
}
