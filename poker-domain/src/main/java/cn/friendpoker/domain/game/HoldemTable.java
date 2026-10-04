package cn.friendpoker.domain.game;

import cn.friendpoker.domain.card.Card;
import cn.friendpoker.domain.card.Deck;
import cn.friendpoker.domain.card.CommittedDeck;
import cn.friendpoker.domain.card.FairShuffle;
import cn.friendpoker.domain.eval.HandValue;
import cn.friendpoker.domain.eval.SevenCardEvaluator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class HoldemTable {
    private final TableConfig config;
    private final Map<Integer, PlayerState> seats = new LinkedHashMap<>();
    private final List<Card> board = new ArrayList<>(5);
    private final LinkedHashSet<Integer> needsAction = new LinkedHashSet<>();
    private final Map<Integer, Long> actedAtBet = new HashMap<>();
    private final List<String> messages = new ArrayList<>();
    private Deck deck;
    private Street street = Street.WAITING;
    private long version;
    private long handNumber;
    private long currentBet;
    private long minimumRaise;
    private int buttonSeat = -1;
    private int smallBlindSeat = -1;
    private int bigBlindSeat = -1;
    private Integer actorSeat;
    private String shuffleCommitment;
    private String shuffleSeed;

    public HoldemTable(TableConfig config) {
        this.config = Objects.requireNonNull(config);
        this.minimumRaise = config.bigBlind();
    }

    public synchronized void sitDown(String playerId, String displayName, int seat, long stack) {
        requireBetweenHands();
        if (seat < 0 || seat >= config.maxPlayers()) {
            throw new GameRuleException("Seat does not exist");
        }
        if (seats.containsKey(seat)) {
            throw new GameRuleException("Seat is occupied");
        }
        if (seats.values().stream().anyMatch(player -> player.id.equals(playerId))) {
            throw new GameRuleException("Player is already seated");
        }
        seats.put(seat, new PlayerState(playerId, displayName, seat, stack));
        version++;
    }

    public synchronized void standUp(String playerId) {
        requireBetweenHands();
        seats.entrySet().removeIf(entry -> entry.getValue().id.equals(playerId));
        version++;
    }

    public synchronized void startHand() {
        prepareHand();
        dealPreparedHand();
    }

    public synchronized void startHand(Deck shuffledDeck) {
        prepareHand(shuffledDeck, null, null);
        dealPreparedHand();
    }

    public synchronized void prepareHand() {
        CommittedDeck committed = FairShuffle.create();
        prepareHand(committed.deck(), committed.commitment(), committed.seed());
    }

    private void prepareHand(Deck shuffledDeck, String commitment, String seed) {
        requireBetweenHands();
        List<Integer> eligible = orderedSeats(player -> player.stack > 0);
        if (eligible.size() < 2) {
            throw new GameRuleException("At least two players with chips are required");
        }

        this.deck = Objects.requireNonNull(shuffledDeck);
        this.shuffleCommitment = commitment;
        this.shuffleSeed = seed;
        board.clear();
        messages.clear();
        needsAction.clear();
        actedAtBet.clear();
        seats.values().forEach(PlayerState::prepareForHand);
        actorSeat = null;
        currentBet = 0;
        minimumRaise = config.bigBlind();
        handNumber++;
        street = Street.SHUFFLE_PENDING;
        messages.add("第 " + handNumber + " 手牌洗牌承诺已公布");
        version++;
    }

    public synchronized void dealPreparedHand() {
        if (street != Street.SHUFFLE_PENDING || deck == null) {
            throw new GameRuleException("当前没有等待发牌的洗牌承诺");
        }
        List<Integer> eligible = orderedSeats(player -> player.stack > 0);
        street = Street.PREFLOP;
        buttonSeat = nextSeat(buttonSeat, player -> player.status == PlayerStatus.ACTIVE);

        if (eligible.size() == 2) {
            smallBlindSeat = buttonSeat;
            bigBlindSeat = nextSeat(smallBlindSeat, player -> player.status == PlayerStatus.ACTIVE);
        } else {
            smallBlindSeat = nextSeat(buttonSeat, player -> player.status == PlayerStatus.ACTIVE);
            bigBlindSeat = nextSeat(smallBlindSeat, player -> player.status == PlayerStatus.ACTIVE);
        }

        int dealSeat = nextSeat(buttonSeat, player -> player.status == PlayerStatus.ACTIVE);
        for (int round = 0; round < 2; round++) {
            int current = dealSeat;
            do {
                seats.get(current).holeCards.add(deck.draw());
                current = nextSeat(current, player -> player.status == PlayerStatus.ACTIVE);
            } while (current != dealSeat);
        }

        postBlind(smallBlindSeat, config.smallBlind(), "小盲");
        postBlind(bigBlindSeat, config.bigBlind(), "大盲");
        // The pre-flop bring-in remains one full big blind even when the big blind is all-in for less.
        currentBet = config.bigBlind();
        orderedSeats(player -> player.status == PlayerStatus.ACTIVE).forEach(needsAction::add);
        actorSeat = needsAction.isEmpty() ? null : nextSeat(bigBlindSeat, player -> needsAction.contains(player.seat));
        messages.add("第 " + handNumber + " 手牌开始发牌");
        version++;
        progressWhenNobodyCanBet();
    }

    public synchronized TablePersistenceState exportState() {
        List<PlayerPersistenceState> players = seats.values().stream()
                .map(player -> new PlayerPersistenceState(player.id, player.name, player.seat, player.stack,
                        player.committedStreet, player.committedHand, player.status, player.holeCards))
                .toList();
        return new TablePersistenceState(players, board, deck == null ? List.of() : deck.cards(),
                deck == null ? 0 : deck.cursor(), street, version, handNumber, currentBet, minimumRaise,
                buttonSeat, smallBlindSeat, bigBlindSeat, actorSeat, needsAction, Set.of(), actedAtBet,
                messages, shuffleCommitment, shuffleSeed);
    }

    public static HoldemTable restore(TableConfig config, TablePersistenceState state) {
        Objects.requireNonNull(state);
        if (state.players().stream().map(PlayerPersistenceState::seat).distinct().count() != state.players().size()
                || state.players().stream().map(PlayerPersistenceState::id).distinct().count() != state.players().size()) {
            throw new IllegalArgumentException("Duplicate player in saved table");
        }
        if (state.players().stream().anyMatch(player -> player.seat() < 0 || player.seat() >= config.maxPlayers()
                || player.stack() < 0 || player.committedStreet() < 0 || player.committedHand() < 0
                || player.holeCards().size() > 2)) {
            throw new IllegalArgumentException("Invalid player in saved table");
        }
        Set<Card> visibleCards = new HashSet<>(state.board());
        int visibleCount = state.board().size();
        for (PlayerPersistenceState player : state.players()) {
            visibleCount += player.holeCards().size();
            visibleCards.addAll(player.holeCards());
        }
        if (visibleCards.size() != visibleCount
                || (!state.deck().isEmpty() && !state.deck().containsAll(visibleCards))) {
            throw new IllegalArgumentException("Invalid cards in saved table");
        }
        if (state.shuffleSeed() != null && state.shuffleCommitment() != null
                && !FairShuffle.commitmentOf(state.shuffleSeed()).equals(state.shuffleCommitment())) {
            throw new IllegalArgumentException("Shuffle commitment does not match saved seed");
        }
        if (state.actorSeat() != null && state.players().stream().noneMatch(p -> p.seat() == state.actorSeat())) {
            throw new IllegalArgumentException("Invalid actor in saved table");
        }
        HoldemTable table = new HoldemTable(config);
        for (PlayerPersistenceState saved : state.players()) {
            PlayerState player = new PlayerState(saved.id(), saved.name(), saved.seat(), saved.stack());
            player.committedStreet = saved.committedStreet();
            player.committedHand = saved.committedHand();
            player.status = saved.status();
            player.holeCards.addAll(saved.holeCards());
            table.seats.put(saved.seat(), player);
        }
        table.board.addAll(state.board());
        table.deck = state.deck().isEmpty() ? null : Deck.restore(state.deck(), state.deckCursor());
        table.street = state.street();
        table.version = state.version();
        table.handNumber = state.handNumber();
        table.currentBet = state.currentBet();
        table.minimumRaise = state.minimumRaise();
        table.buttonSeat = state.buttonSeat();
        table.smallBlindSeat = state.smallBlindSeat();
        table.bigBlindSeat = state.bigBlindSeat();
        table.actorSeat = state.actorSeat();
        table.needsAction.addAll(state.needsAction());
        table.actedAtBet.putAll(state.actedAtBet());
        state.actedSinceFullRaise().forEach(seat -> table.actedAtBet.putIfAbsent(seat, state.currentBet()));
        table.messages.addAll(state.messages());
        table.shuffleCommitment = state.shuffleCommitment();
        table.shuffleSeed = state.shuffleSeed();
        return table;
    }

    public synchronized void act(String playerId, ActionType action, long amount, long expectedVersion) {
        if (expectedVersion != version) {
            throw new GameRuleException("牌桌状态已变化，请按最新状态操作");
        }
        if (actorSeat == null || street == Street.WAITING || street == Street.COMPLETE) {
            throw new GameRuleException("当前没有可执行的操作");
        }
        PlayerState player = seats.get(actorSeat);
        if (!player.id.equals(playerId)) {
            throw new GameRuleException("还没有轮到你");
        }
        if (player.status != PlayerStatus.ACTIVE) {
            throw new GameRuleException("当前玩家不能行动");
        }

        switch (action) {
            case FOLD -> fold(player);
            case CHECK -> check(player);
            case CALL -> call(player);
            case BET, RAISE, ALL_IN -> wager(player, action, amount);
        }
        version++;

        if (playersStillInHand().size() == 1) {
            awardFoldWin(playersStillInHand().getFirst());
            return;
        }

        needsAction.removeIf(seat -> seats.get(seat).status != PlayerStatus.ACTIVE);
        if (needsAction.isEmpty()) {
            finishBettingRound();
        } else {
            actorSeat = nextSeat(player.seat, candidate -> needsAction.contains(candidate.seat));
        }
    }

    public synchronized TableSnapshot snapshotFor(String viewerId) {
        boolean showdown = street == Street.COMPLETE && board.size() == 5;
        List<PlayerSnapshot> playerViews = seats.values().stream()
                .sorted(Comparator.comparingInt(player -> player.seat))
                .map(player -> {
                    boolean reveal = player.id.equals(viewerId)
                            || (showdown && player.status != PlayerStatus.FOLDED && player.holeCards.size() == 2);
                    return new PlayerSnapshot(player.id, player.name, player.seat, player.stack,
                            player.committedStreet, player.committedHand, player.status,
                            reveal ? player.holeCards : List.of(), player.seat == buttonSeat,
                            player.seat == smallBlindSeat, player.seat == bigBlindSeat);
                }).toList();
        PlayerState viewer = seats.values().stream().filter(player -> player.id.equals(viewerId)).findFirst().orElse(null);
        Set<ActionType> legal = viewer != null && actorSeat != null && viewer.seat == actorSeat
                ? legalActions(viewer) : Set.of();
        long pot = seats.values().stream().mapToLong(player -> player.committedHand).sum();
        return new TableSnapshot(version, handNumber, street, pot, currentBet, minimumRaise, actorSeat,
                board, playerViews, legal, messages.size() <= 30 ? messages : messages.subList(messages.size() - 30, messages.size()),
                shuffleCommitment, street == Street.COMPLETE ? shuffleSeed : null);
    }

    public synchronized boolean isBetweenHands() {
        return street == Street.WAITING || street == Street.COMPLETE;
    }

    public synchronized boolean isPlayersTurn(String playerId) {
        return actorSeat != null && seats.get(actorSeat).id.equals(playerId);
    }

    private void fold(PlayerState player) {
        player.status = PlayerStatus.FOLDED;
        needsAction.remove(player.seat);
        actedAtBet.put(player.seat, currentBet);
        messages.add(player.name + " 弃牌");
    }

    private void check(PlayerState player) {
        if (player.committedStreet != currentBet) {
            throw new GameRuleException("有待跟注筹码，不能过牌");
        }
        needsAction.remove(player.seat);
        actedAtBet.put(player.seat, currentBet);
        messages.add(player.name + " 过牌");
    }

    private void call(PlayerState player) {
        long owed = currentBet - player.committedStreet;
        if (owed <= 0) {
            throw new GameRuleException("无需跟注，可以过牌");
        }
        long paid = player.commit(owed);
        needsAction.remove(player.seat);
        actedAtBet.put(player.seat, currentBet);
        messages.add(player.name + " 跟注 " + paid + (player.status == PlayerStatus.ALL_IN ? "（All-in）" : ""));
    }

    private void wager(PlayerState player, ActionType action, long requestedTarget) {
        long allInTarget = player.committedStreet + player.stack;
        long target = action == ActionType.ALL_IN ? allInTarget : requestedTarget;
        if (target <= currentBet) {
            if (action == ActionType.ALL_IN && target > player.committedStreet) {
                call(player);
                return;
            }
            throw new GameRuleException("下注额必须高于当前下注");
        }
        if (target > allInTarget) {
            throw new GameRuleException("筹码不足");
        }
        if (!hasRaiseRights(player)) {
            throw new GameRuleException("短码 All-in 没有重新开放你的加注权");
        }

        long previousBet = currentBet;
        long raiseSize = target - previousBet;
        boolean fullRaise = previousBet == 0 ? target >= minimumRaise : raiseSize >= minimumRaise;
        if (!fullRaise && target != allInTarget) {
            throw new GameRuleException("至少需要加注 " + minimumRaise);
        }

        player.commit(target - player.committedStreet);
        currentBet = target;
        if (fullRaise) {
            minimumRaise = previousBet == 0 ? target : raiseSize;
            needsAction.clear();
            orderedSeats(candidate -> candidate.status == PlayerStatus.ACTIVE && candidate.seat != player.seat)
                    .forEach(needsAction::add);
        } else {
            orderedSeats(candidate -> candidate.status == PlayerStatus.ACTIVE
                    && candidate.seat != player.seat && candidate.committedStreet < currentBet).forEach(needsAction::add);
        }
        actedAtBet.put(player.seat, currentBet);
        needsAction.remove(player.seat);
        String label = previousBet == 0 ? "下注" : "加注到";
        messages.add(player.name + " " + label + " " + target
                + (player.status == PlayerStatus.ALL_IN ? "（All-in）" : ""));
    }

    private Set<ActionType> legalActions(PlayerState player) {
        EnumSet<ActionType> actions = EnumSet.of(ActionType.FOLD, ActionType.ALL_IN);
        if (player.committedStreet == currentBet) {
            actions.add(ActionType.CHECK);
            if (currentBet == 0 && player.stack > 0 && hasRaiseRights(player)) {
                actions.add(ActionType.BET);
            }
        } else {
            actions.add(ActionType.CALL);
            if (player.committedStreet + player.stack > currentBet && hasRaiseRights(player)) {
                actions.add(ActionType.RAISE);
            }
        }
        return actions;
    }

    private boolean hasRaiseRights(PlayerState player) {
        Long lastFacedBet = actedAtBet.get(player.seat);
        return lastFacedBet == null || currentBet - lastFacedBet >= minimumRaise;
    }

    private void finishBettingRound() {
        if (street == Street.RIVER) {
            showdown();
            return;
        }
        advanceStreet();
        progressWhenNobodyCanBet();
    }

    private void advanceStreet() {
        seats.values().forEach(player -> player.committedStreet = 0);
        currentBet = 0;
        minimumRaise = config.bigBlind();
        actedAtBet.clear();
        needsAction.clear();

        switch (street) {
            case PREFLOP -> {
                deck.burn();
                board.add(deck.draw());
                board.add(deck.draw());
                board.add(deck.draw());
                street = Street.FLOP;
                messages.add("翻牌 " + board);
            }
            case FLOP -> {
                deck.burn();
                board.add(deck.draw());
                street = Street.TURN;
                messages.add("转牌 " + board.getLast());
            }
            case TURN -> {
                deck.burn();
                board.add(deck.draw());
                street = Street.RIVER;
                messages.add("河牌 " + board.getLast());
            }
            default -> throw new IllegalStateException("Cannot advance from " + street);
        }
        orderedSeats(player -> player.status == PlayerStatus.ACTIVE).forEach(needsAction::add);
        actorSeat = needsAction.isEmpty() ? null : nextSeat(buttonSeat, player -> needsAction.contains(player.seat));
    }

    private void progressWhenNobodyCanBet() {
        List<PlayerState> able = playersAbleToBet();
        while (street != Street.COMPLETE
                && (able.isEmpty() || (able.size() == 1 && able.getFirst().committedStreet >= currentBet))) {
            needsAction.clear();
            actorSeat = null;
            if (street == Street.RIVER) {
                showdown();
            } else {
                advanceStreet();
            }
            able = playersAbleToBet();
        }
    }

    private void showdown() {
        Map<String, Long> commitments = new LinkedHashMap<>();
        Set<String> folded = new HashSet<>();
        seats.values().forEach(player -> {
            if (player.committedHand > 0) {
                commitments.put(player.id, player.committedHand);
            }
            if (player.status == PlayerStatus.FOLDED) {
                folded.add(player.id);
            }
        });

        for (PotSlice pot : PotCalculator.calculate(commitments, folded)) {
            List<PlayerState> eligible = seats.values().stream()
                    .filter(player -> pot.eligiblePlayerIds().contains(player.id)).toList();
            if (eligible.isEmpty()) {
                continue;
            }
            Map<PlayerState, HandValue> values = new HashMap<>();
            for (PlayerState player : eligible) {
                List<Card> seven = new ArrayList<>(board);
                seven.addAll(player.holeCards);
                values.put(player, SevenCardEvaluator.evaluate(seven));
            }
            HandValue best = values.values().stream().max(HandValue::compareTo).orElseThrow();
            List<PlayerState> winners = values.entrySet().stream().filter(entry -> entry.getValue().equals(best))
                    .map(Map.Entry::getKey).sorted(Comparator.comparingInt(this::distanceLeftOfButton)).toList();
            long share = pot.amount() / winners.size();
            long remainder = pot.amount() % winners.size();
            for (int i = 0; i < winners.size(); i++) {
                winners.get(i).stack += share + (i < remainder ? 1 : 0);
            }
            messages.add(winners.stream().map(player -> player.name).reduce((a, b) -> a + "、" + b).orElse("")
                    + " 以" + best.category().displayName() + "赢得 " + pot.amount());
        }
        completeHand();
    }

    private void awardFoldWin(PlayerState winner) {
        long pot = seats.values().stream().mapToLong(player -> player.committedHand).sum();
        winner.stack += pot;
        messages.add(winner.name + " 赢得底池 " + pot);
        completeHand();
    }

    private void completeHand() {
        street = Street.COMPLETE;
        actorSeat = null;
        currentBet = 0;
        needsAction.clear();
        seats.values().forEach(player -> {
            player.committedStreet = 0;
            player.committedHand = 0;
        });
    }

    private void postBlind(int seat, long blind, String label) {
        PlayerState player = seats.get(seat);
        long paid = player.commit(blind);
        messages.add(player.name + " 支付" + label + " " + paid);
    }

    private List<PlayerState> playersStillInHand() {
        return seats.values().stream().filter(player -> player.status != PlayerStatus.FOLDED
                && player.status != PlayerStatus.SITTING_OUT).toList();
    }

    private List<PlayerState> playersAbleToBet() {
        return seats.values().stream().filter(player -> player.status == PlayerStatus.ACTIVE).toList();
    }

    private List<Integer> orderedSeats(Predicate<PlayerState> predicate) {
        return seats.values().stream().filter(predicate).map(player -> player.seat).sorted().toList();
    }

    private int nextSeat(int afterSeat, Predicate<PlayerState> predicate) {
        for (int offset = 1; offset <= config.maxPlayers(); offset++) {
            int candidateSeat = Math.floorMod(afterSeat + offset, config.maxPlayers());
            PlayerState candidate = seats.get(candidateSeat);
            if (candidate != null && predicate.test(candidate)) {
                return candidateSeat;
            }
        }
        throw new GameRuleException("No eligible seat found");
    }

    private int distanceLeftOfButton(PlayerState player) {
        return Math.floorMod(player.seat - buttonSeat - 1, config.maxPlayers());
    }

    private void requireBetweenHands() {
        if (!isBetweenHands()) {
            throw new GameRuleException("只能在两手牌之间调整座位");
        }
    }
}
