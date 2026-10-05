package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;
import cn.friendpoker.domain.eval.FiveCardEvaluator;
import cn.friendpoker.domain.eval.HandCategory;
import cn.friendpoker.domain.eval.HandValue;
import cn.friendpoker.domain.eval.SevenCardEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/** A small policy that only consumes the acting bot's public/private player view. */
public final class PokerBotPolicy {
    private PokerBotPolicy() {
    }

    /** BET and RAISE amounts are the target street contribution, not an increment. */
    public record Decision(ActionType action, long amount) {
    }

    public static Decision choose(TableSnapshot view, String botId, long bigBlind, RandomGenerator random) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(random, "random");
        PlayerSnapshot bot = validateView(view, botId, bigBlind);
        Set<ActionType> legal = view.legalActions();
        double strength = strength(bot.cards(), view.board());
        long owed = view.currentBet() - bot.committedStreet();
        long maxTarget = bot.committedStreet() + bot.stack();

        // ponytail: basic hand strength, not an equity solver; add opponent/range modelling only if needed.
        ActionType wager = view.currentBet() == 0 ? ActionType.BET : ActionType.RAISE;
        long increment = Math.max(view.minimumRaise(), Math.max(bigBlind, view.pot() / 2));
        if (strength >= 0.68 && legal.contains(wager) && increment < maxTarget - view.currentBet()
                && random.nextDouble() < 0.3) {
            return new Decision(wager, view.currentBet() + increment);
        }
        if (legal.contains(ActionType.CHECK) && owed == 0) {
            return new Decision(ActionType.CHECK, 0);
        }
        if (legal.contains(ActionType.CALL) && owed > 0) {
            long cost = Math.min(owed, bot.stack());
            double price = (double) cost / ((double) view.pot() + cost);
            if (strength >= 0.28 + price * 0.65 || !legal.contains(ActionType.FOLD)) {
                return new Decision(ActionType.CALL, 0);
            }
        }
        if (legal.contains(ActionType.FOLD)) {
            return new Decision(ActionType.FOLD, 0);
        }
        // HoldemTable lists ALL_IN even when a short raise has not reopened raising rights.
        // Only use that fallback as a call; never infer raising rights from ALL_IN alone.
        if (legal.contains(ActionType.ALL_IN) && maxTarget <= view.currentBet()) {
            return new Decision(ActionType.ALL_IN, 0);
        }
        throw new IllegalArgumentException("The player view has no safe legal action");
    }

    private static PlayerSnapshot validateView(TableSnapshot view, String botId, long bigBlind) {
        int boardSize = switch (view.street()) {
            case PREFLOP -> 0;
            case FLOP -> 3;
            case TURN -> 4;
            case RIVER -> 5;
            default -> throw new IllegalArgumentException("Bots can only act during a betting street");
        };
        PlayerSnapshot bot = view.players().stream().filter(player -> player.id().equals(botId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Bot is not seated"));
        if (view.actorSeat() == null || bot.seat() != view.actorSeat() || bot.status() != PlayerStatus.ACTIVE
                || bot.cards().size() != 2 || view.board().size() != boardSize || view.legalActions().isEmpty()
                || view.players().stream().anyMatch(player -> !player.id().equals(botId) && !player.cards().isEmpty())) {
            throw new IllegalArgumentException("An acting bot's own snapshotFor view is required");
        }
        List<Card> visible = new ArrayList<>(view.board());
        visible.addAll(bot.cards());
        if (visible.stream().distinct().count() != visible.size() || bigBlind <= 0 || view.minimumRaise() <= 0
                || view.pot() < 0 || view.currentBet() < 0 || bot.stack() <= 0 || bot.committedStreet() < 0
                || bot.committedStreet() > view.currentBet() || bot.committedHand() < bot.committedStreet()
                || bot.stack() > Long.MAX_VALUE - bot.committedStreet()) {
            throw new IllegalArgumentException("Invalid cards or chip amounts in bot view");
        }
        return bot;
    }

    private static double strength(List<Card> hole, List<Card> board) {
        if (board.isEmpty()) {
            int high = Math.max(hole.get(0).rank().value(), hole.get(1).rank().value());
            int low = Math.min(hole.get(0).rank().value(), hole.get(1).rank().value());
            if (high == low) return 0.55 + high * 0.03;
            return Math.min(0.95, 0.16 + (high + low - 4) / 40.0
                    + (hole.get(0).suit() == hole.get(1).suit() ? 0.06 : 0)
                    + (high - low == 1 ? 0.04 : 0));
        }
        List<Card> cards = new ArrayList<>(board);
        cards.addAll(hole);
        HandValue best;
        if (cards.size() == 7) {
            best = SevenCardEvaluator.evaluate(cards);
        } else if (cards.size() == 5) {
            best = FiveCardEvaluator.evaluate(cards);
        } else {
            best = null;
            for (int i = 0; i < cards.size(); i++) {
                List<Card> five = new ArrayList<>(cards);
                five.remove(i);
                HandValue value = FiveCardEvaluator.evaluate(five);
                if (best == null || value.compareTo(best) > 0) best = value;
            }
        }
        if (best.category() == HandCategory.HIGH_CARD) return 0.18 + best.kickers().getFirst() / 100.0;
        if (best.category() == HandCategory.ONE_PAIR) return 0.4 + best.kickers().getFirst() * 0.018;
        return Math.min(0.98, 0.62 + best.category().ordinal() * 0.06);
    }
}
