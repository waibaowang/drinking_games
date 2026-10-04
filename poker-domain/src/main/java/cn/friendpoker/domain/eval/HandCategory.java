package cn.friendpoker.domain.eval;

public enum HandCategory {
    HIGH_CARD("高牌"),
    ONE_PAIR("一对"),
    TWO_PAIR("两对"),
    THREE_OF_A_KIND("三条"),
    STRAIGHT("顺子"),
    FLUSH("同花"),
    FULL_HOUSE("葫芦"),
    FOUR_OF_A_KIND("四条"),
    STRAIGHT_FLUSH("同花顺");

    private final String displayName;

    HandCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

