package cn.friendpoker.domain.card;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FairShuffle {
    private FairShuffle() {}

    public static CommittedDeck create() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        List<Card> cards = new ArrayList<>(52);
        for (Suit suit : Suit.values()) for (Rank rank : Rank.values()) cards.add(new Card(rank, suit));
        HashRandom random = new HashRandom(seed);
        for (int i = cards.size() - 1; i > 0; i--) Collections.swap(cards, i, random.nextInt(i + 1));
        return new CommittedDeck(new Deck(cards), hex(sha256(seed)), hex(seed));
    }

    public static String commitmentOf(String seedHex) { return hex(sha256(fromHex(seedHex))); }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format("%02x", value));
        return builder.toString();
    }

    private static byte[] fromHex(String value) {
        if (value == null || value.length() % 2 != 0) throw new IllegalArgumentException("Invalid hex value");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16), low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Invalid hex value");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static final class HashRandom {
        private final byte[] seed;
        private long counter;
        private HashRandom(byte[] seed) { this.seed = seed.clone(); }
        int nextInt(int bound) {
            long range = 1L << 32, limit = range - range % bound;
            while (true) {
                byte[] input = ByteBuffer.allocate(seed.length + Long.BYTES).put(seed).putLong(counter++).array();
                long candidate = Integer.toUnsignedLong(ByteBuffer.wrap(sha256(input)).getInt());
                if (candidate < limit) return (int) (candidate % bound);
            }
        }
    }
}

