package cn.friendpoker.domain.game;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PotCalculatorTest {
    @Test
    void createsMainAndSidePotsAndExcludesFoldedPlayersFromWinning() {
        Map<String, Long> commitments = new LinkedHashMap<>();
        commitments.put("short", 100L);
        commitments.put("deep", 300L);
        commitments.put("folded", 300L);

        List<PotSlice> pots = PotCalculator.calculate(commitments, Set.of("folded"));

        assertEquals(2, pots.size());
        assertEquals(300, pots.get(0).amount());
        assertEquals(Set.of("short", "deep"), pots.get(0).eligiblePlayerIds());
        assertEquals(400, pots.get(1).amount());
        assertEquals(Set.of("deep"), pots.get(1).eligiblePlayerIds());
    }
}

