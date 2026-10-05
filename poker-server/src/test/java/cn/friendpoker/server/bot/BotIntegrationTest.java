package cn.friendpoker.server.bot;

import cn.friendpoker.domain.flight.FlightGame;
import cn.friendpoker.domain.flight.FlightPersistenceState;
import cn.friendpoker.domain.flight.FlightPhase;
import cn.friendpoker.domain.flight.FlightPlayerSnapshot;
import cn.friendpoker.domain.game.ActionType;
import cn.friendpoker.domain.game.GameRuleException;
import cn.friendpoker.domain.game.HoldemTable;
import cn.friendpoker.domain.game.PlayerPersistenceState;
import cn.friendpoker.domain.game.PlayerStatus;
import cn.friendpoker.domain.game.Street;
import cn.friendpoker.domain.game.TablePersistenceState;
import cn.friendpoker.server.PokerApplication;
import cn.friendpoker.server.flight.CreateFlightRoomRequest;
import cn.friendpoker.server.flight.FlightCoordinator;
import cn.friendpoker.server.flight.FlightRoom;
import cn.friendpoker.server.flight.FlightRoomService;
import cn.friendpoker.server.flight.FlightRoomStore;
import cn.friendpoker.server.flight.FlightWebSocketController;
import cn.friendpoker.server.room.CreateRoomRequest;
import cn.friendpoker.server.room.GameCoordinator;
import cn.friendpoker.server.room.GameType;
import cn.friendpoker.server.room.GameWebSocketController;
import cn.friendpoker.server.room.PokerRoom;
import cn.friendpoker.server.room.RoomService;
import cn.friendpoker.server.room.RoomStatus;
import cn.friendpoker.server.room.RoomStore;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import cn.friendpoker.server.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = PokerApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:bot-integration-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureMockMvc
class BotIntegrationTest {
    @Autowired RoomService pokerRooms;
    @Autowired FlightRoomService flightRooms;
    @Autowired RoomStore pokerStore;
    @Autowired FlightRoomStore flightStore;
    @Autowired GameCoordinator poker;
    @Autowired FlightCoordinator flight;
    @Autowired GameWebSocketController pokerSocket;
    @Autowired FlightWebSocketController flightSocket;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @MockitoBean BotScheduler scheduler;
    private String host;
    private String guest;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        host = "bot_host_" + suffix;
        guest = "bot_guest_" + suffix;
        String hash = passwords.encode("password123");
        users.save(new UserAccount(host, hash, "Host", UserRole.PLAYER));
        users.save(new UserAccount(guest, hash, "Guest", UserRole.PLAYER));
        when(scheduler.hasHuman(any(UUID.class))).thenReturn(true);
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void onlyTheHostCanAddRemoveOrFillBotsBetweenGames(GameType type) {
        Fixture fixture = create(type);
        assertThrows(IllegalArgumentException.class, () -> add(fixture, host, null));
        sitHost(fixture);
        assertThrows(IllegalArgumentException.class, () -> add(fixture, guest, 0));
        assertThrows(IllegalArgumentException.class, () -> settings(fixture, guest, false, BotSettings.Speed.FAST));
        add(fixture, host, 0);
        assertEquals(1, roster(fixture).size());
        assertEquals(0, roster(fixture).getFirst().seat());
        assertTrue(roster(fixture).getFirst().id().startsWith("bot:"));
        Object before = gameState(fixture);
        long version = version(fixture);
        assertThrows(IllegalArgumentException.class, () -> add(fixture, host, 1));
        assertFrozen(fixture, before, version);
        add(fixture, host, null);
        assertEquals(3, roster(fixture).size());
        assertThrows(IllegalArgumentException.class, () -> add(fixture, host, null));
        remove(fixture, host, roster(fixture).getFirst().id());
        assertEquals(2, roster(fixture).size());
        add(fixture, host, null);
        assertEquals(3, roster(fixture).size());
        assertThrows(IllegalArgumentException.class, () -> remove(fixture, host, host));
        assertEquals(4, playerCount(fixture));
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void activeGamesRejectRosterChangesWithoutPartialUpdates(GameType type) {
        Fixture fixture = ready(type);
        activate(fixture);
        Object before = gameState(fixture);
        long version = version(fixture);
        List<BotSeat> roster = roster(fixture);
        assertThrows(GameRuleException.class, () -> add(fixture, host, null));
        assertThrows(GameRuleException.class, () -> remove(fixture, host, roster.getFirst().id()));
        assertFrozen(fixture, before, version);
        assertEquals(roster, roster(fixture));
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void botRostersAndSettingsRestoreWithoutBecomingAccountMembers(GameType type) {
        Fixture fixture = ready(type);
        settings(fixture, host, false, BotSettings.Speed.FAST);
        assertEquals(200, settings(fixture).delayMillis());
        if (type == GameType.POKER) {
            PokerRoom restored = restoredPoker(fixture);
            assertEquals(gameState(fixture), restored.table().exportState());
            assertEquals(roster(fixture), restored.bots());
            assertEquals(settings(fixture), restored.botSettings());
            assertTrue(restored.recovered());
            assertFalse(restored.hasMember(restored.bots().getFirst().id()));
        } else {
            FlightRoom restored = restoredFlight(fixture);
            assertEquals(gameState(fixture), restored.game().exportState());
            assertEquals(roster(fixture), restored.bots());
            assertEquals(settings(fixture), restored.botSettings());
            assertTrue(restored.recovered());
            assertFalse(restored.hasMember(restored.bots().getFirst().id()));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts where username like 'bot:%'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from " + memberTable(type)
                + " where room_id = ? and username like 'bot:%'", Integer.class, fixture.id().toString()));
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void legacySnapshotsMissingBotFieldsRestoreWithDefaults(GameType type) throws Exception {
        Fixture fixture = create(type);
        sitHost(fixture);
        String legacy = json.writeValueAsString(Map.of(type == GameType.POKER ? "table" : "game",
                gameState(fixture), "recentActionIds", List.of()));
        jdbc.update("update " + roomTable(type) + " set state_json = ? where id = ?", legacy, fixture.id().toString());
        if (type == GameType.POKER) {
            PokerRoom restored = restoredPoker(fixture);
            assertEquals(gameState(fixture), restored.table().exportState());
            assertTrue(restored.bots().isEmpty());
            assertEquals(BotSettings.DEFAULT, restored.botSettings());
        } else {
            FlightRoom restored = restoredFlight(fixture);
            assertEquals(gameState(fixture), restored.game().exportState());
            assertTrue(restored.bots().isEmpty());
            assertEquals(BotSettings.DEFAULT, restored.botSettings());
        }
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void pausedOfflineStaleOrRepeatedJobsDoNotAdvanceGames(GameType type) {
        Fixture fixture = ready(type);
        activate(fixture);
        String actor = roster(fixture).getFirst().id();
        Object before = gameState(fixture);
        long version = version(fixture);
        step(fixture, version - 1, actor, "stale-job");
        step(fixture, version, "bot:unknown", "foreign-job");
        step(fixture, version, host, "human-job");
        assertFrozen(fixture, before, version);
        when(scheduler.hasHuman(fixture.id())).thenReturn(false);
        step(fixture, version, actor, "offline-job");
        assertFrozen(fixture, before, version);
        when(scheduler.hasHuman(fixture.id())).thenReturn(true);
        settings(fixture, host, false, null);
        long pausedVersion = version(fixture);
        step(fixture, pausedVersion, actor, "paused-job");
        assertFrozen(fixture, before, pausedVersion);
        if (type == GameType.POKER) {
            PokerRoom room = pokerRooms.get(fixture.id());
            assertNull(room.actionDeadline());
            ReflectionTestUtils.invokeMethod(poker, "timeout", fixture.id(), room.table().snapshotFor("").version());
            assertFrozen(fixture, before, pausedVersion);
        }
        settings(fixture, host, true, null);
        long resumedVersion = version(fixture);
        step(fixture, resumedVersion, actor, "accepted-job");
        assertEquals(resumedVersion + 1, version(fixture));
        assertNotEquals(before, gameState(fixture));
        Object accepted = gameState(fixture);
        step(fixture, resumedVersion, actor, "accepted-job");
        assertFrozen(fixture, accepted, resumedVersion + 1);
        step(fixture, resumedVersion + 1, actor, "accepted-job");
        assertFrozen(fixture, accepted, resumedVersion + 1);
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void closingRoomsCancelsBotsAndMakesEvenCurrentVersionJobsHarmless(GameType type) {
        Fixture fixture = ready(type);
        activate(fixture);
        close(fixture);
        Object before = gameState(fixture);
        long version = version(fixture);
        step(fixture, version, roster(fixture).getFirst().id(), "closed-job");
        assertFrozen(fixture, before, version);
        assertThrows(IllegalArgumentException.class, () -> settings(fixture, host, true, null));
        verify(scheduler, atLeastOnce()).cancel(fixture.id());
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void fillDatabaseConflictsLeaveBothRosterAndGameUntouched(GameType type) {
        Fixture fixture = create(type);
        sitHost(fixture);
        Object before = gameState(fixture);
        long version = version(fixture);
        conflict(fixture);
        assertThrows(ConcurrencyFailureException.class, () -> add(fixture, host, null));
        assertFrozen(fixture, before, version);
        assertTrue(roster(fixture).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void botDatabaseConflictsDoNotAdvanceMemoryAndRetryTheSameJob(GameType type) {
        Fixture fixture = ready(type);
        activate(fixture);
        Object before = gameState(fixture);
        long version = version(fixture);
        conflict(fixture);
        clearInvocations(scheduler);
        step(fixture, version, roster(fixture).getFirst().id(), "save-failure-job");
        assertFrozen(fixture, before, version);
        verify(scheduler).schedule(eq(fixture.id()), eq(version), eq(2_000L), any(Runnable.class));
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void completedBotGamesWriteHistoryOnlyForRealAccounts(GameType type) {
        Fixture fixture = ready(type);
        long accountCount = users.count();
        activate(fixture);
        String actor = roster(fixture).getFirst().id();
        if (type == GameType.FLIGHT) {
            FlightPersistenceState state = flightRooms.get(fixture.id()).game().exportState();
            List<FlightPlayerSnapshot> players = state.players().stream().map(player -> player.id().equals(actor)
                    ? new FlightPlayerSnapshot(player.id(), player.name(), player.seat(), List.of(55, 57, 57, 57), true)
                    : player).toList();
            installFlight(fixture, FlightGame.restore(new FlightPersistenceState(players, FlightPhase.MOVE,
                    state.version() + 1, state.gameNumber(), state.roundNumber(), 0, 2, Set.of(), List.of(),
                    1, state.messages(), null)));
        } else {
            installPoker(fixture, riverWithOneBotAction(pokerRooms.get(fixture.id()), actor));
        }
        step(fixture, version(fixture), actor, "finish-bot-job");
        if (type == GameType.FLIGHT) assertEquals(FlightPhase.COMPLETE, flightRooms.get(fixture.id()).game().snapshotFor("").phase());
        else assertEquals(Street.COMPLETE, pokerRooms.get(fixture.id()).table().snapshotFor("").street());
        assertEquals(1, jdbc.queryForObject("select count(*) from hand_records where room_id = ?",
                Integer.class, fixture.id().toString()));
        assertEquals(List.of(host), jdbc.queryForList("select p.username from hand_record_players p join hand_records h"
                + " on p.hand_id = h.id where h.room_id = ?", String.class, fixture.id().toString()));
        String summary = jdbc.queryForObject("select summary from hand_records where room_id = ?", String.class, fixture.id().toString());
        assertTrue(summary.contains(roster(fixture).getFirst().name()));
        assertEquals(accountCount, users.count());
        assertEquals(0, jdbc.queryForObject("select count(*) from user_accounts where username like 'bot:%'", Integer.class));
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void httpBotManagementReturnsNoContentAndRejectsForgedBotAccounts(GameType type) throws Exception {
        Fixture fixture = ready(type);
        mvc.perform(get("/api/rooms/{id}", fixture.id()).with(user(host).roles("PLAYER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bots[0].id").value(roster(fixture).getFirst().id()));
        mvc.perform(post("/api/rooms/{id}/bots", fixture.id()).with(csrf()).with(user(host).roles("PLAYER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seat\":3}"))
                .andExpect(status().isNoContent());
        String bot = roster(fixture).stream().filter(candidate -> candidate.seat() == 3).findFirst().orElseThrow().id();
        mvc.perform(delete("/api/rooms/{id}/bots/{bot}", fixture.id(), bot).with(csrf()).with(user(host).roles("PLAYER")))
                .andExpect(status().isNoContent());
        mvc.perform(patch("/api/rooms/{id}/bots/settings", fixture.id()).with(csrf()).with(user(host).roles("PLAYER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"speed\":\"FAST\"}"))
                .andExpect(status().isNoContent());
        assertEquals(new BotSettings(false, BotSettings.Speed.FAST), settings(fixture));
        mvc.perform(post("/api/rooms/{id}/bots/fill", fixture.id()).with(csrf()).with(user(guest).roles("PLAYER")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms/{id}/bots/fill", fixture.id()).with(csrf()).with(user(host).roles("PLAYER")))
                .andExpect(status().isNoContent());
        Object before = gameState(fixture);
        long version = version(fixture);
        String forged = roster(fixture).getFirst().id();
        mvc.perform(get("/api/rooms/{id}", fixture.id()).with(user(forged).roles("PLAYER")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/rooms/{id}/seats/2", fixture.id()).with(csrf()).with(user(forged).roles("PLAYER")))
                .andExpect(status().isUnauthorized());
        assertFrozen(fixture, before, version);
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void socketActionsUseTheAuthenticatedHumanNotTheBotWhoseTurnItIs(GameType type) {
        Fixture fixture = ready(type);
        activate(fixture);
        Object before = gameState(fixture);
        long version = version(fixture);
        if (type == GameType.FLIGHT) {
            long gameVersion = flightRooms.get(fixture.id()).game().snapshotFor(host).version();
            assertThrows(GameRuleException.class, () -> flightSocket.action(fixture.id(),
                    new FlightWebSocketController.ActionMessage("spoof-flight", "ROLL", null, gameVersion), () -> host));
            assertThrows(IllegalArgumentException.class, () -> flight.act(fixture.id(), roster(fixture).getFirst().id(),
                    "spoof-flight-id", "ROLL", null, gameVersion));
        } else {
            long gameVersion = pokerRooms.get(fixture.id()).table().snapshotFor(host).version();
            assertThrows(GameRuleException.class, () -> pokerSocket.action(fixture.id(),
                    new GameWebSocketController.ActionMessage("spoof-poker", ActionType.CALL, 0, gameVersion), () -> host));
            assertThrows(IllegalArgumentException.class, () -> poker.act(fixture.id(), roster(fixture).getFirst().id(),
                    "spoof-poker-id", ActionType.CALL, 0, gameVersion));
        }
        assertFrozen(fixture, before, version);
    }

    @Test
    void botActionAndPokerTimeoutRacingAcceptOnlyTheBotAction() throws Exception {
        Fixture fixture = ready(GameType.POKER);
        activate(fixture);
        PokerRoom room = pokerRooms.get(fixture.id());
        long roomVersion = room.stateVersion();
        long tableVersion = room.table().snapshotFor("").version();
        String actor = roster(fixture).getFirst().id();
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var bot = executor.submit(() -> {
                gate.await();
                step(fixture, roomVersion, actor, "timeout-race-bot");
                return null;
            });
            var timeout = executor.submit(() -> {
                gate.await();
                ReflectionTestUtils.invokeMethod(poker, "timeout", fixture.id(), tableVersion);
                return null;
            });
            gate.countDown();
            bot.get(); timeout.get();
        }
        assertEquals(roomVersion + 1, room.stateVersion());
        assertTrue(room.hasActionId("timeout-race-bot"));
        assertFalse(room.recentActionIds().stream().anyMatch(id -> id.startsWith("timeout:")));
    }

    private Fixture create(GameType type) {
        UUID id;
        if (type == GameType.POKER) {
            PokerRoom room = pokerRooms.create(host, new CreateRoomRequest("机器人测试", "", 10, 20, 1_000, 4, 120));
            pokerRooms.join(room.id(), guest, "");
            id = room.id();
        } else {
            FlightRoom room = flightRooms.create(host, new CreateFlightRoomRequest("机器人测试", ""));
            flightRooms.join(room.id(), guest, "");
            id = room.id();
        }
        return new Fixture(type, id);
    }

    private Fixture ready(GameType type) {
        Fixture fixture = create(type);
        sitHost(fixture);
        add(fixture, host, 0);
        return fixture;
    }

    private void sitHost(Fixture fixture) {
        if (fixture.type() == GameType.POKER) poker.sit(fixture.id(), host, "Host", 1);
        else flight.sit(fixture.id(), host, "Host", 1);
    }

    private void activate(Fixture fixture) {
        if (fixture.type() == GameType.POKER) {
            PokerRoom room = pokerRooms.get(fixture.id());
            HoldemTable candidate = HoldemTable.restore(room.config(), room.table().exportState());
            candidate.prepareHand();
            candidate.dealPreparedHand();
            installPoker(fixture, candidate);
        } else flight.start(fixture.id(), host);
        assertEquals(0, fixture.type() == GameType.POKER
                ? pokerRooms.get(fixture.id()).table().snapshotFor("").actorSeat()
                : flightRooms.get(fixture.id()).game().snapshotFor("").currentSeat());
    }

    private HoldemTable riverWithOneBotAction(PokerRoom room, String actor) {
        TablePersistenceState saved = room.table().exportState();
        List<PlayerPersistenceState> players = saved.players().stream().map(player -> new PlayerPersistenceState(
                player.id(), player.name(), player.seat(), player.id().equals(actor) ? 800 : 0, 0,
                player.id().equals(actor) ? 200 : 1_000,
                player.id().equals(actor) ? PlayerStatus.ACTIVE : PlayerStatus.ALL_IN, player.holeCards())).toList();
        return HoldemTable.restore(room.config(), new TablePersistenceState(players,
                saved.deck().subList(saved.deckCursor(), saved.deckCursor() + 5), saved.deck(), saved.deckCursor() + 5,
                Street.RIVER, saved.version() + 1, saved.handNumber(), 0, room.config().bigBlind(),
                saved.buttonSeat(), saved.smallBlindSeat(), saved.bigBlindSeat(), 0, Set.of(0), Set.of(), Map.of(),
                saved.messages(), saved.shuffleCommitment(), saved.shuffleSeed()));
    }

    private void installPoker(Fixture fixture, HoldemTable candidate) {
        PokerRoom room = pokerRooms.get(fixture.id());
        long version = pokerStore.saveState(room, candidate, room.recentActionIds(), null, RoomStatus.ACTIVE);
        room.install(candidate, room.recentActionIds(), null, RoomStatus.ACTIVE, version);
    }

    private void installFlight(Fixture fixture, FlightGame candidate) {
        FlightRoom room = flightRooms.get(fixture.id());
        long version = flightStore.saveState(room, candidate, room.recentActionIds(), RoomStatus.ACTIVE);
        room.install(candidate, room.recentActionIds(), RoomStatus.ACTIVE, version);
    }

    private void add(Fixture fixture, String user, Integer seat) {
        if (fixture.type() == GameType.POKER) poker.addBots(fixture.id(), user, seat);
        else flight.addBots(fixture.id(), user, seat);
    }

    private void remove(Fixture fixture, String user, String bot) {
        if (fixture.type() == GameType.POKER) poker.removeBot(fixture.id(), user, bot);
        else flight.removeBot(fixture.id(), user, bot);
    }

    private void settings(Fixture fixture, String user, Boolean enabled, BotSettings.Speed speed) {
        if (fixture.type() == GameType.POKER) poker.botSettings(fixture.id(), user, enabled, speed);
        else flight.botSettings(fixture.id(), user, enabled, speed);
    }

    private BotSettings settings(Fixture fixture) {
        return fixture.type() == GameType.POKER ? pokerRooms.get(fixture.id()).botSettings()
                : flightRooms.get(fixture.id()).botSettings();
    }

    private void step(Fixture fixture, long version, String actor, String actionId) {
        if (fixture.type() == GameType.POKER) poker.botStep(fixture.id(), version, actor, actionId, 0);
        else flight.botStep(fixture.id(), version, actor, actionId, 0);
    }

    private void close(Fixture fixture) {
        if (fixture.type() == GameType.POKER) poker.close(fixture.id(), host);
        else flight.close(fixture.id(), host);
    }

    private void conflict(Fixture fixture) {
        jdbc.update("update " + roomTable(fixture.type()) + " set state_version = state_version + 1 where id = ?",
                fixture.id().toString());
    }

    private Object gameState(Fixture fixture) {
        return fixture.type() == GameType.POKER ? pokerRooms.get(fixture.id()).table().exportState()
                : flightRooms.get(fixture.id()).game().exportState();
    }

    private long version(Fixture fixture) {
        return fixture.type() == GameType.POKER ? pokerRooms.get(fixture.id()).stateVersion()
                : flightRooms.get(fixture.id()).stateVersion();
    }

    private List<BotSeat> roster(Fixture fixture) {
        return fixture.type() == GameType.POKER ? pokerRooms.get(fixture.id()).bots() : flightRooms.get(fixture.id()).bots();
    }

    private int playerCount(Fixture fixture) {
        return fixture.type() == GameType.POKER ? pokerRooms.get(fixture.id()).table().snapshotFor("").players().size()
                : flightRooms.get(fixture.id()).game().snapshotFor("").players().size();
    }

    private void assertFrozen(Fixture fixture, Object game, long version) {
        assertEquals(game, gameState(fixture));
        assertEquals(version, version(fixture));
    }

    private PokerRoom restoredPoker(Fixture fixture) {
        return pokerStore.loadActive().stream().filter(room -> room.id().equals(fixture.id())).findFirst().orElseThrow();
    }

    private FlightRoom restoredFlight(Fixture fixture) {
        return flightStore.loadActive().stream().filter(room -> room.id().equals(fixture.id())).findFirst().orElseThrow();
    }

    private static String roomTable(GameType type) { return type == GameType.POKER ? "poker_rooms" : "flight_rooms"; }
    private static String memberTable(GameType type) { return type == GameType.POKER ? "room_members" : "flight_room_members"; }
    private record Fixture(GameType type, UUID id) {}
}
