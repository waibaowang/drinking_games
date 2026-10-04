package cn.friendpoker.domain.game;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PotCalculator {
    private PotCalculator() {
    }

    public static List<PotSlice> calculate(Map<String, Long> commitments, Set<String> foldedPlayerIds) {
        List<Long> levels = commitments.values().stream().filter(value -> value > 0)
                .distinct().sorted().toList();
        List<PotSlice> pots = new ArrayList<>();
        long previous = 0;
        for (long level : levels) {
            List<Map.Entry<String, Long>> contributors = commitments.entrySet().stream()
                    .filter(entry -> entry.getValue() >= level)
                    .sorted(Map.Entry.comparingByKey()).toList();
            long amount = (level - previous) * contributors.size();
            Set<String> eligible = contributors.stream()
                    .map(Map.Entry::getKey)
                    .filter(id -> !foldedPlayerIds.contains(id))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (amount > 0) {
                pots.add(new PotSlice(amount, eligible));
            }
            previous = level;
        }
        return pots;
    }
}
