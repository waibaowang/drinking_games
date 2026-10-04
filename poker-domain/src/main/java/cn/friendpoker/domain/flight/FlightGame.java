package cn.friendpoker.domain.flight;

import cn.friendpoker.domain.game.GameRuleException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

public final class FlightGame {
    public static final int MAX_PLAYERS = 4;
    public static final int PIECES_PER_PLAYER = 4;
    public static final int HANGAR = -1;
    public static final int FINISHED = 57;
    private static final int TRACK_SIZE = 52;
    private static final int ITEM_SPAWN_INTERVAL = 2;
    private static final int ITEM_BATCH_SIZE = 2;
    private static final int ITEM_LIFETIME_ROUNDS = 3;
    private static final int MAX_ITEMS = 4;
    private static final int MESSAGE_LIMIT = 50;

    private final Map<Integer, Player> seats = new LinkedHashMap<>();
    private final Set<Integer> completedSeats = new LinkedHashSet<>();
    private final List<FlightBoardItem> boardItems = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final RandomGenerator random;
    private FlightPhase phase = FlightPhase.WAITING;
    private long version;
    private long gameNumber;
    private long roundNumber;
    private Integer currentSeat;
    private Integer lastRoll;
    private long nextItemId = 1;
    private String winnerId;

    public FlightGame() {
        this(new SecureRandom());
    }

    FlightGame(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    public synchronized void sitDown(String id, String name, int seat) {
        requireBetweenGames();
        if (seat < 0 || seat >= MAX_PLAYERS) throw new GameRuleException("颜色座位不存在");
        if (seats.containsKey(seat)) throw new GameRuleException("这个颜色已经有人了");
        if (seats.values().stream().anyMatch(player -> player.id.equals(id))) {
            throw new GameRuleException("你已经选择了颜色");
        }
        seats.put(seat, new Player(id, name, seat, freshPieces()));
        addMessage(name + " 选择了" + colorName(seat));
        version++;
    }

    public synchronized void standUp(String id) {
        requireBetweenGames();
        Player player = findPlayer(id);
        seats.remove(player.seat);
        addMessage(player.name + " 离开了座位");
        version++;
    }

    public synchronized void start() {
        requireBetweenGames();
        if (seats.size() < 2) throw new GameRuleException("至少需要两名玩家");
        seats.values().forEach(player -> player.pieces = freshPieces());
        completedSeats.clear();
        boardItems.clear();
        messages.clear();
        phase = FlightPhase.ROLL;
        gameNumber++;
        roundNumber = 1;
        currentSeat = seats.keySet().stream().min(Integer::compareTo).orElseThrow();
        lastRoll = null;
        winnerId = null;
        nextItemId = 1;
        addMessage("第 " + gameNumber + " 局开始，" + seats.get(currentSeat).name + " 先掷骰子");
        version++;
    }

    public synchronized void roll(String playerId, long expectedVersion) {
        roll(playerId, expectedVersion, random.nextInt(1, 7));
    }

    synchronized void roll(String playerId, long expectedVersion, int die) {
        requireVersion(expectedVersion);
        requireTurn(playerId, FlightPhase.ROLL);
        if (die < 1 || die > 6) throw new IllegalArgumentException("骰子点数不合法");
        lastRoll = die;
        Player player = seats.get(currentSeat);
        addMessage(player.name + " 掷出了 " + die);
        version++;
        if (legalPieces(player, die).isEmpty()) {
            addMessage(player.name + " 没有可以移动的飞机");
            if (die != 6) finishTurn();
        } else {
            phase = FlightPhase.MOVE;
        }
    }

    public synchronized void move(String playerId, int pieceIndex, long expectedVersion) {
        requireVersion(expectedVersion);
        requireTurn(playerId, FlightPhase.MOVE);
        Player player = seats.get(currentSeat);
        if (!legalPieces(player, lastRoll).contains(pieceIndex)) throw new GameRuleException("这架飞机当前不能移动");

        int before = player.pieces.get(pieceIndex);
        int destination = before == HANGAR ? 0 : advance(before, lastRoll);
        player.pieces.set(pieceIndex, destination);
        addMessage(player.name + " 移动了 " + lastRoll + " 格");
        if (destination >= 0 && destination < TRACK_SIZE) {
            capture(player, destination);
            collectItem(player, destination);
        }
        version++;

        if (player.pieces.stream().allMatch(progress -> progress == FINISHED)) {
            phase = FlightPhase.COMPLETE;
            winnerId = player.id;
            currentSeat = null;
            addMessage(player.name + " 获得了本局胜利");
        } else if (lastRoll == 6) {
            phase = FlightPhase.ROLL;
            addMessage(player.name + " 可以继续掷骰子");
        } else {
            finishTurn();
        }
    }

    public synchronized FlightSnapshot snapshotFor(String viewer) {
        List<Integer> legal = phase == FlightPhase.MOVE && currentSeat != null
                && seats.get(currentSeat).id.equals(viewer) ? legalPieces(seats.get(currentSeat), lastRoll) : List.of();
        return new FlightSnapshot(version, gameNumber, roundNumber, phase, currentSeat, lastRoll, legal,
                playerSnapshots(), boardItems, messages, winnerId);
    }

    public synchronized FlightPersistenceState exportState() {
        return new FlightPersistenceState(playerSnapshots(), phase, version, gameNumber, roundNumber, currentSeat,
                lastRoll, completedSeats, boardItems, nextItemId, messages, winnerId);
    }

    public static FlightGame restore(FlightPersistenceState state) {
        Objects.requireNonNull(state);
        if (state.phase() == null || state.version() < 0 || state.gameNumber() < 0 || state.roundNumber() < 0
                || state.nextItemId() < 1 || state.messages().size() > MESSAGE_LIMIT) {
            throw new IllegalArgumentException("保存的飞行棋基础状态不合法");
        }
        if (state.players().size() > MAX_PLAYERS
                || state.players().stream().map(FlightPlayerSnapshot::seat).distinct().count() != state.players().size()
                || state.players().stream().map(FlightPlayerSnapshot::id).distinct().count() != state.players().size()
                || state.players().stream().anyMatch(player -> player.id() == null || player.id().isBlank()
                || player.name() == null || player.name().isBlank())) {
            throw new IllegalArgumentException("保存的飞行棋玩家不合法");
        }
        if (state.players().stream().anyMatch(player -> player.seat() < 0 || player.seat() >= MAX_PLAYERS
                || player.pieces().size() != PIECES_PER_PLAYER
                || player.pieces().stream().anyMatch(progress -> progress < HANGAR || progress > FINISHED))) {
            throw new IllegalArgumentException("保存的飞行棋棋子不合法");
        }
        if (state.currentSeat() != null && state.players().stream().noneMatch(p -> p.seat() == state.currentSeat())) {
            throw new IllegalArgumentException("保存的当前玩家不合法");
        }
        if (state.lastRoll() != null && (state.lastRoll() < 1 || state.lastRoll() > 6)) {
            throw new IllegalArgumentException("保存的骰子点数不合法");
        }
        Set<Integer> occupiedSeats = state.players().stream().map(FlightPlayerSnapshot::seat)
                .collect(java.util.stream.Collectors.toSet());
        if (!occupiedSeats.containsAll(state.completedSeats())
                || state.currentSeat() != null && state.completedSeats().contains(state.currentSeat())) {
            throw new IllegalArgumentException("保存的轮次进度不合法");
        }
        boolean active = state.phase() == FlightPhase.ROLL || state.phase() == FlightPhase.MOVE;
        if (active && (state.players().size() < 2 || state.currentSeat() == null
                || state.gameNumber() < 1 || state.roundNumber() < 1 || state.winnerId() != null)
                || state.phase() == FlightPhase.MOVE && state.lastRoll() == null
                || !active && state.currentSeat() != null
                || state.phase() == FlightPhase.WAITING && (state.gameNumber() != 0 || state.roundNumber() != 0
                || state.lastRoll() != null || state.winnerId() != null || !state.completedSeats().isEmpty()
                || !state.boardItems().isEmpty())
                || state.phase() == FlightPhase.COMPLETE && (state.gameNumber() < 1 || state.roundNumber() < 1
                || state.winnerId() == null || state.winnerId().isBlank())) {
            throw new IllegalArgumentException("保存的飞行棋阶段不合法");
        }
        long greatestItemId = state.boardItems().stream().mapToLong(FlightBoardItem::id).max().orElse(0);
        if (state.boardItems().size() > MAX_ITEMS
                || state.boardItems().stream().map(FlightBoardItem::id).distinct().count() != state.boardItems().size()
                || state.boardItems().stream().map(FlightBoardItem::cellIndex).distinct().count() != state.boardItems().size()
                || state.nextItemId() <= greatestItemId
                || state.boardItems().stream().anyMatch(item -> item.id() < 1 || !"MYSTERY".equals(item.type())
                || item.cellIndex() < 0 || item.cellIndex() >= TRACK_SIZE || item.spawnRound() < 2
                || item.spawnRound() % ITEM_SPAWN_INTERVAL != 0
                || item.expiresAtRound() != item.spawnRound() + ITEM_LIFETIME_ROUNDS
                || item.spawnRound() > state.roundNumber() || item.expiresAtRound() <= state.roundNumber())) {
            throw new IllegalArgumentException("保存的道具不合法");
        }

        FlightGame game = new FlightGame();
        state.players().forEach(saved -> game.seats.put(saved.seat(),
                new Player(saved.id(), saved.name(), saved.seat(), new ArrayList<>(saved.pieces()))));
        game.phase = state.phase();
        game.version = state.version();
        game.gameNumber = state.gameNumber();
        game.roundNumber = state.roundNumber();
        game.currentSeat = state.currentSeat();
        game.lastRoll = state.lastRoll();
        game.completedSeats.addAll(state.completedSeats());
        game.boardItems.addAll(state.boardItems());
        game.nextItemId = state.nextItemId();
        game.messages.addAll(state.messages());
        game.winnerId = state.winnerId();
        return game;
    }

    public static int globalCell(int seat, int progress) {
        if (progress < 0 || progress >= TRACK_SIZE) return -1;
        return Math.floorMod(seat * 13 + progress, TRACK_SIZE);
    }

    private int advance(int progress, int die) {
        int destination = progress + die;
        if (destination > FINISHED) destination = FINISHED - (destination - FINISHED);
        if (destination == 18) return 30;
        if (destination > 0 && destination <= 47 && destination % 4 == 0) return destination + 4;
        return destination;
    }

    private List<Integer> legalPieces(Player player, int die) {
        List<Integer> legal = new ArrayList<>();
        for (int index = 0; index < player.pieces.size(); index++) {
            int progress = player.pieces.get(index);
            if (progress == FINISHED) continue;
            if (progress != HANGAR || die == 5 || die == 6) legal.add(index);
        }
        return legal;
    }

    private void capture(Player moving, int progress) {
        int cell = globalCell(moving.seat, progress);
        for (Player opponent : seats.values()) {
            if (opponent == moving) continue;
            for (int index = 0; index < opponent.pieces.size(); index++) {
                int opponentProgress = opponent.pieces.get(index);
                if (opponentProgress >= 0 && opponentProgress < TRACK_SIZE
                        && globalCell(opponent.seat, opponentProgress) == cell) {
                    opponent.pieces.set(index, HANGAR);
                    addMessage(moving.name + " 将 " + opponent.name + " 的飞机送回了机场");
                }
            }
        }
    }

    private void collectItem(Player player, int progress) {
        int cell = globalCell(player.seat, progress);
        FlightBoardItem item = boardItems.stream().filter(candidate -> candidate.cellIndex() == cell)
                .findFirst().orElse(null);
        if (item != null) {
            boardItems.remove(item);
            addMessage(player.name + " 捡到了神秘道具（效果待开放）");
        }
    }

    private void finishTurn() {
        completedSeats.add(currentSeat);
        if (completedSeats.containsAll(seats.keySet())) {
            roundNumber++;
            completedSeats.clear();
            expireItems();
            if (roundNumber >= 2 && roundNumber % ITEM_SPAWN_INTERVAL == 0) spawnItems();
            addMessage("进入第 " + roundNumber + " 轮");
        }
        currentSeat = nextSeat(currentSeat);
        phase = FlightPhase.ROLL;
    }

    private void expireItems() {
        int before = boardItems.size();
        boardItems.removeIf(item -> item.expiresAtRound() <= roundNumber);
        if (boardItems.size() < before) addMessage("未被拾取的道具消失了");
    }

    private void spawnItems() {
        Set<Integer> occupied = new HashSet<>();
        seats.values().forEach(player -> player.pieces.stream().filter(progress -> progress >= 0 && progress < TRACK_SIZE)
                .map(progress -> globalCell(player.seat, progress)).forEach(occupied::add));
        boardItems.forEach(item -> occupied.add(item.cellIndex()));
        Set<Integer> excluded = new HashSet<>(occupied);
        for (int seat = 0; seat < MAX_PLAYERS; seat++) {
            excluded.add(globalCell(seat, 0));
            excluded.add(globalCell(seat, 18));
            excluded.add(globalCell(seat, 30));
        }
        List<Integer> eligible = new ArrayList<>();
        for (int cell = 0; cell < TRACK_SIZE; cell++) if (!excluded.contains(cell)) eligible.add(cell);
        int count = Math.min(Math.min(ITEM_BATCH_SIZE, MAX_ITEMS - boardItems.size()), eligible.size());
        for (int i = 0; i < count; i++) {
            int selected = random.nextInt(eligible.size());
            int cell = eligible.remove(selected);
            boardItems.add(new FlightBoardItem(nextItemId++, "MYSTERY", cell, roundNumber,
                    roundNumber + ITEM_LIFETIME_ROUNDS));
        }
        if (count > 0) addMessage("棋盘上刷新了 " + count + " 个神秘道具");
    }

    private Integer nextSeat(int seat) {
        return seats.keySet().stream().filter(candidate -> candidate > seat).min(Integer::compareTo)
                .orElseGet(() -> seats.keySet().stream().min(Integer::compareTo).orElseThrow());
    }

    private List<FlightPlayerSnapshot> playerSnapshots() {
        return seats.values().stream().map(player -> new FlightPlayerSnapshot(player.id, player.name, player.seat,
                player.pieces, Objects.equals(currentSeat, player.seat))).toList();
    }

    private Player findPlayer(String id) {
        return seats.values().stream().filter(player -> player.id.equals(id)).findFirst()
                .orElseThrow(() -> new GameRuleException("你还没有选择颜色"));
    }

    private void requireTurn(String playerId, FlightPhase expectedPhase) {
        if (phase != expectedPhase || currentSeat == null) throw new GameRuleException("当前不能执行这个操作");
        if (!seats.get(currentSeat).id.equals(playerId)) throw new GameRuleException("还没有轮到你");
    }

    private void requireVersion(long expectedVersion) {
        if (version != expectedVersion) throw new GameRuleException("棋盘状态已变化，请按最新状态操作");
    }

    private void requireBetweenGames() {
        if (phase != FlightPhase.WAITING && phase != FlightPhase.COMPLETE) {
            throw new GameRuleException("游戏进行中不能更换颜色");
        }
    }

    private void addMessage(String message) {
        messages.add(message);
        while (messages.size() > MESSAGE_LIMIT) messages.removeFirst();
    }

    private static List<Integer> freshPieces() {
        return new ArrayList<>(List.of(HANGAR, HANGAR, HANGAR, HANGAR));
    }

    private static String colorName(int seat) {
        return List.of("红色", "黄色", "蓝色", "绿色").get(seat);
    }

    private static final class Player {
        private final String id;
        private final String name;
        private final int seat;
        private List<Integer> pieces;

        private Player(String id, String name, int seat, List<Integer> pieces) {
            this.id = id;
            this.name = name;
            this.seat = seat;
            this.pieces = pieces;
        }
    }
}
