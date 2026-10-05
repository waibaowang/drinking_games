package cn.friendpoker.server.bot;

public record BotSettings(boolean enabled, Speed speed) {
    public static final BotSettings DEFAULT = new BotSettings(true, Speed.NORMAL);
    public BotSettings { if (speed == null) speed = Speed.NORMAL; }
    public long delayMillis() { return speed == Speed.FAST ? 200 : 800; }
    public enum Speed { NORMAL, FAST }
}
