package cn.friendpoker.domain.game;

import java.util.Set;

public record PotSlice(long amount, Set<String> eligiblePlayerIds) {
    public PotSlice {
        eligiblePlayerIds = Set.copyOf(eligiblePlayerIds);
    }
}

