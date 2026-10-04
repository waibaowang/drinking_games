package cn.friendpoker.domain.eval;

import java.util.List;
import java.util.Objects;

public record HandValue(HandCategory category, List<Integer> kickers) implements Comparable<HandValue> {
    public HandValue {
        Objects.requireNonNull(category, "category");
        kickers = List.copyOf(kickers);
    }

    @Override
    public int compareTo(HandValue other) {
        int categoryComparison = Integer.compare(category.ordinal(), other.category.ordinal());
        if (categoryComparison != 0) {
            return categoryComparison;
        }
        for (int i = 0; i < Math.min(kickers.size(), other.kickers.size()); i++) {
            int comparison = Integer.compare(kickers.get(i), other.kickers.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(kickers.size(), other.kickers.size());
    }
}

