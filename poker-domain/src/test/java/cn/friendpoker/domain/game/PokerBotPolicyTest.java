package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;
import cn.friendpoker.domain.card.Deck;
import cn.friendpoker.domain.card.Rank;
import cn.friendpoker.domain.card.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PokerBotPolicyTest {
    @Test
    void evaluatesEveryStreetAndUsuallyChecksWhenCheckingIsFree() {
        for (Street street : List.of(Street.PREFLOP, Street.FLOP, Street.TURN, Street.RIVER)) {
            TableSnapshot view = view(street, 0, 0, 1_000, 20, Set.of(ActionType.CHECK, ActionType.BET),
                    cards("AS", "AH"));
            assertEquals(new PokerBotPolicy.Decision(ActionType.CHECK, 0),
                    PokerBotPolicy.choose(view, "bot", 20, neverRaise()));
        }
    }

    @Test
    void strongHandsCanWagerUsingTheTargetContributionAndAtLeastTheMinimumRaise() {
        TableSnapshot raise = view(Street.PREFLOP, 100, 20, 1_000, 120,
                Set.of(ActionType.CALL, ActionType.FOLD, ActionType.RAISE), cards("AS", "AH"));
        PokerBotPolicy.Decision choice = PokerBotPolicy.choose(raise, "bot", 20, alwaysRaise());
        assertEquals(ActionType.RAISE, choice.action());
        assertEquals(220, choice.amount());

        TableSnapshot bet = view(Street.FLOP, 0, 0, 1_000, 80,
                Set.of(ActionType.CHECK, ActionType.BET), cards("2S", "2H"));
        choice = PokerBotPolicy.choose(bet, "bot", 20, alwaysRaise());
        assertEquals(ActionType.BET, choice.action());
        assertEquals(80, choice.amount());
    }

    @Test
    void shortStacksCallAllInButDoNotDefaultToAnAllInRaise() {
        TableSnapshot call = view(Street.PREFLOP, 100, 20, 5, 80,
                Set.of(ActionType.CALL, ActionType.FOLD, ActionType.ALL_IN), cards("AS", "AH"));
        assertEquals(new PokerBotPolicy.Decision(ActionType.CALL, 0),
                PokerBotPolicy.choose(call, "bot", 20, alwaysRaise()));

        TableSnapshot check = view(Street.FLOP, 0, 0, 5, 20,
                Set.of(ActionType.CHECK, ActionType.BET, ActionType.ALL_IN), cards("AS", "AH"));
        assertEquals(new PokerBotPolicy.Decision(ActionType.CHECK, 0),
                PokerBotPolicy.choose(check, "bot", 20, alwaysRaise()));

        TableSnapshot exactMinimum = view(Street.PREFLOP, 100, 20, 160, 80,
                Set.of(ActionType.CALL, ActionType.FOLD, ActionType.RAISE), cards("AS", "AH"));
        assertEquals(ActionType.CALL, PokerBotPolicy.choose(exactMinimum, "bot", 20, alwaysRaise()).action());
    }

    @Test
    void weakCardsFoldAnExpensiveCallAndRespectRestrictedLegalActions() {
        TableSnapshot weak = view(Street.PREFLOP, 500, 0, 1_000, 20,
                Set.of(ActionType.CALL, ActionType.FOLD), cards("2S", "7H"));
        assertEquals(ActionType.FOLD, PokerBotPolicy.choose(weak, "bot", 20, neverRaise()).action());
        for (ActionType only : List.of(ActionType.FOLD, ActionType.CALL)) {
            TableSnapshot limited = view(Street.PREFLOP, 500, 0, 1_000, 20, Set.of(only), cards("2S", "7H"));
            assertEquals(only, PokerBotPolicy.choose(limited, "bot", 20, alwaysRaise()).action());
        }
        TableSnapshot allInCall = view(Street.PREFLOP, 100, 0, 5, 80,
                Set.of(ActionType.ALL_IN), cards("AS", "AH"));
        assertEquals(ActionType.ALL_IN, PokerBotPolicy.choose(allInCall, "bot", 20, alwaysRaise()).action());
    }

    @Test
    void shortAllInDoesNotReopenTheBotsRaisingRights() {
        HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 6, 30));
        table.sitDown("bot", "Bot", 0, 1_000);
        table.sitDown("short", "Short", 1, 150);
        table.sitDown("big", "Big", 2, 1_000);
        table.sitDown("raiser", "Raiser", 3, 1_000);
        List<Card> deck = freshCards();
        place(deck, 3, cards("AS").getFirst());
        place(deck, 7, cards("AH").getFirst());
        table.startHand(new Deck(deck));
        act(table, "raiser", ActionType.RAISE, 100);
        act(table, "bot", ActionType.CALL, 0);
        act(table, "short", ActionType.ALL_IN, 0);
        act(table, "big", ActionType.FOLD, 0);
        act(table, "raiser", ActionType.CALL, 0);

        TableSnapshot own = table.snapshotFor("bot");
        assertFalse(own.legalActions().contains(ActionType.RAISE));
        assertTrue(own.legalActions().contains(ActionType.ALL_IN));
        PokerBotPolicy.Decision choice = PokerBotPolicy.choose(own, "bot", 20, alwaysRaise());
        assertEquals(ActionType.CALL, choice.action());
        table.act("bot", choice.action(), choice.amount(), own.version());
        assertEquals(Street.FLOP, table.snapshotFor("bot").street());
    }

    @Test
    void rejectsAnotherPlayersViewExposedHoleCardsAndNonActingStates() {
        HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 6, 30));
        table.sitDown("bot", "Bot", 0, 1_000);
        table.sitDown("human", "Human", 1, 1_000);
        assertThrows(IllegalArgumentException.class,
                () -> PokerBotPolicy.choose(table.snapshotFor("bot"), "bot", 20, neverRaise()));
        table.startHand();
        assertThrows(IllegalArgumentException.class,
                () -> PokerBotPolicy.choose(table.snapshotFor("human"), "bot", 20, neverRaise()));
        assertThrows(IllegalArgumentException.class,
                () -> PokerBotPolicy.choose(table.snapshotFor("bot"), "unknown", 20, neverRaise()));

        TableSnapshot own = table.snapshotFor("bot");
        List<PlayerSnapshot> exposed = new ArrayList<>(own.players());
        exposed.set(1, table.snapshotFor("human").players().get(1));
        TableSnapshot leaked = new TableSnapshot(own.version(), own.handNumber(), own.street(), own.pot(),
                own.currentBet(), own.minimumRaise(), own.actorSeat(), own.board(), exposed,
                own.legalActions(), own.messages(), own.shuffleCommitment(), own.shuffleSeedReveal());
        assertThrows(IllegalArgumentException.class,
                () -> PokerBotPolicy.choose(leaked, "bot", 20, neverRaise()));
    }

    @Test
    void rejectsInvalidBoardBeforeCallingAnEvaluator() {
        TableSnapshot own = view(Street.TURN, 0, 0, 1_000, 20, Set.of(ActionType.CHECK), cards("AS", "AH"));
        TableSnapshot duplicate = new TableSnapshot(own.version(), own.handNumber(), own.street(), own.pot(),
                own.currentBet(), own.minimumRaise(), own.actorSeat(), cards("AS", "3H", "4D", "5C"),
                own.players(), own.legalActions(), own.messages(), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> PokerBotPolicy.choose(duplicate, "bot", 20, neverRaise()));
        assertThrows(IllegalArgumentException.class,
                () -> PokerBotPolicy.choose(own, "bot", 0, neverRaise()));
    }

    @Test
    void randomMultiHandGamesKeepCardsPrivateConserveChipsAndFinishLegally() {
        Random random = new Random(7_643);
        Set<Street> visited = EnumSet.noneOf(Street.class);
        int finishedHands = 0;
        for (int game = 0; game < 80; game++) {
            int playerCount = 2 + random.nextInt(8);
            HoldemTable table = new HoldemTable(new TableConfig(10, 20, 1_000, 9, 30));
            long total = 0;
            for (int i = 0; i < playerCount; i++) {
                long chips = 5 + random.nextInt(996);
                total += chips;
                table.sitDown("bot" + i, "Bot " + i, i, chips);
            }
            for (int hand = 0; hand < 4; hand++) {
                if (table.snapshotFor("").players().stream().filter(p -> p.stack() > 0).count() < 2) break;
                List<Card> deck = freshCards();
                Collections.shuffle(deck, random);
                table.startHand(new Deck(deck));
                int actions = 0;
                while (!table.isBetweenHands() && actions < 400) {
                    TableSnapshot publicView = table.snapshotFor("");
                    PlayerSnapshot actor = publicView.players().stream()
                            .filter(p -> p.seat() == publicView.actorSeat()).findFirst().orElseThrow();
                    TableSnapshot own = table.snapshotFor(actor.id());
                    assertTrue(own.players().stream().filter(p -> !p.id().equals(actor.id()))
                            .allMatch(p -> p.cards().isEmpty()));
                    visited.add(own.street());
                    PokerBotPolicy.Decision choice = PokerBotPolicy.choose(own, actor.id(), 20, random);
                    assertTrue(own.legalActions().contains(choice.action()));
                    assertFalse(choice.action() == ActionType.ALL_IN);
                    if (choice.action() == ActionType.BET || choice.action() == ActionType.RAISE) {
                        assertTrue(choice.amount() - own.currentBet() >= own.minimumRaise());
                        assertTrue(choice.amount() < actor.committedStreet() + actor.stack());
                    }
                    table.act(actor.id(), choice.action(), choice.amount(), own.version());
                    assertEquals(total, table.snapshotFor("").players().stream()
                            .mapToLong(p -> p.stack() + p.committedHand()).sum());
                    actions++;
                }
                assertTrue(table.isBetweenHands(), "Bot hand must terminate");
                finishedHands++;
            }
        }
        assertEquals(EnumSet.of(Street.PREFLOP, Street.FLOP, Street.TURN, Street.RIVER), visited);
        assertTrue(finishedHands >= 80);
    }

    private static TableSnapshot view(Street street, long bet, long committed, long stack, long minimum,
                                      Set<ActionType> legal, List<Card> hole) {
        List<Card> board = switch (street) {
            case PREFLOP -> List.of();
            case FLOP -> cards("2D", "3D", "4C");
            case TURN -> cards("2D", "3D", "4C", "5H");
            case RIVER -> cards("2D", "3D", "4C", "5H", "9S");
            default -> List.of();
        };
        PlayerSnapshot bot = new PlayerSnapshot("bot", "Bot", 0, stack, committed, committed,
                PlayerStatus.ACTIVE, hole, true, false, false);
        return new TableSnapshot(1, 1, street, 100, bet, minimum, 0, board, List.of(bot), legal,
                List.of(), null, null);
    }

    private static Random neverRaise() {
        return new Random(1) {
            @Override public double nextDouble() { return 0.99; }
        };
    }

    private static Random alwaysRaise() {
        return new Random(1) {
            @Override public double nextDouble() { return 0; }
        };
    }

    private static List<Card> cards(String... codes) {
        List<Card> result = new ArrayList<>();
        for (String code : codes) {
            Rank rank = java.util.Arrays.stream(Rank.values())
                    .filter(r -> r.symbol().equals(code.substring(0, 1))).findFirst().orElseThrow();
            Suit suit = switch (code.charAt(1)) {
                case 'S' -> Suit.SPADES;
                case 'H' -> Suit.HEARTS;
                case 'D' -> Suit.DIAMONDS;
                case 'C' -> Suit.CLUBS;
                default -> throw new IllegalArgumentException("Invalid card");
            };
            result.add(new Card(rank, suit));
        }
        return result;
    }

    private static List<Card> freshCards() {
        List<Card> cards = new ArrayList<>();
        for (Suit suit : Suit.values()) for (Rank rank : Rank.values()) cards.add(new Card(rank, suit));
        return cards;
    }

    private static void place(List<Card> deck, int index, Card card) {
        Collections.swap(deck, index, deck.indexOf(card));
    }

    private static void act(HoldemTable table, String id, ActionType action, long target) {
        table.act(id, action, target, table.snapshotFor(id).version());
    }
}
