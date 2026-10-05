package cn.friendpoker.server.bot;

import cn.friendpoker.server.flight.FlightCoordinator;
import cn.friendpoker.server.flight.FlightRoom;
import cn.friendpoker.server.flight.FlightRoomService;
import cn.friendpoker.server.room.GameCoordinator;
import cn.friendpoker.server.room.GameType;
import cn.friendpoker.server.room.PokerRoom;
import cn.friendpoker.server.room.RoomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BotPresenceTest {
    private final BotScheduler scheduler = mock(BotScheduler.class);
    private final RoomService pokerRooms = mock(RoomService.class);
    private final FlightRoomService flightRooms = mock(FlightRoomService.class);
    private final GameCoordinator poker = mock(GameCoordinator.class);
    private final FlightCoordinator flight = mock(FlightCoordinator.class);
    private final BotPresence presence = new BotPresence(scheduler, pokerRooms, flightRooms, poker, flight);
    private final Principal human = () -> "alice";

    @ParameterizedTest
    @EnumSource(GameType.class)
    void firstValidStateSubscriptionRegistersPresenceBeforeResumingTheRecoveredRoom(GameType type) {
        UUID id = UUID.randomUUID();
        String destination = destination(type, id);
        if (type == GameType.POKER) {
            PokerRoom restored = mock(PokerRoom.class);
            when(restored.recovered()).thenReturn(true);
            when(pokerRooms.requireMember(id, human.getName())).thenReturn(restored);
            when(pokerRooms.find(id)).thenReturn(Optional.of(restored));
        } else {
            FlightRoom restored = mock(FlightRoom.class);
            when(restored.recovered()).thenReturn(true);
            when(flightRooms.requireMember(id, human.getName())).thenReturn(restored);
            when(pokerRooms.find(id)).thenReturn(Optional.empty());
        }
        verifyNoInteractions(scheduler, poker, flight);

        presence.subscribed(new SessionSubscribeEvent(this,
                message(StompCommand.SUBSCRIBE, "new-tab", "state", destination, human), human));

        InOrder order = inOrder(scheduler, poker, flight);
        order.verify(scheduler).subscribe("new-tab", "state", id, human);
        if (type == GameType.POKER) {
            verify(pokerRooms).requireMember(id, human.getName());
            order.verify(poker).resumeBots(id);
            verifyNoInteractions(flight);
        } else {
            verify(flightRooms).requireMember(id, human.getName());
            order.verify(flight).resumeBots(id);
            verifyNoInteractions(poker);
        }
    }

    @ParameterizedTest
    @EnumSource(GameType.class)
    void foreignRoomStateQueuesCannotCountAsHumanPresence(GameType type) {
        UUID id = UUID.randomUUID();
        if (type == GameType.POKER) {
            when(pokerRooms.requireMember(id, human.getName())).thenThrow(new IllegalArgumentException("not a member"));
        } else {
            when(flightRooms.requireMember(id, human.getName())).thenThrow(new IllegalArgumentException("not a member"));
        }

        presence.subscribed(new SessionSubscribeEvent(this,
                message(StompCommand.SUBSCRIBE, "foreign-tab", "state", destination(type, id), human), human));

        verifyNoInteractions(scheduler, poker, flight);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/user/queue/rooms/%s/chat",
            "/user/queue/rooms/%s/shuffle",
            "/user/queue/flight-rooms/%s/chat",
            "/user/queue/flight-rooms/%s/shuffle",
            "/queue/rooms/%s",
            "/topic/rooms/%s",
            "/user/queue/errors",
            "/user/queue/rooms/not-a-room",
            "/user/queue/rooms/------------------------------------"
    })
    void chatShufflePublicAndMalformedQueuesDoNotResumeBots(String pattern) {
        String destination = pattern.formatted(UUID.randomUUID());

        presence.subscribed(new SessionSubscribeEvent(this,
                message(StompCommand.SUBSCRIBE, "tab", "ignored", destination, human), human));

        verifyNoInteractions(scheduler, pokerRooms, flightRooms, poker, flight);
    }

    @Test
    void missingPrincipalOrDestinationCannotCreatePresence() {
        UUID id = UUID.randomUUID();
        presence.subscribed(new SessionSubscribeEvent(this,
                message(StompCommand.SUBSCRIBE, "tab", "state", destination(GameType.POKER, id), null), null));
        presence.subscribed(new SessionSubscribeEvent(this,
                message(StompCommand.SUBSCRIBE, "tab", "state", null, human), human));
        verifyNoInteractions(scheduler, pokerRooms, flightRooms, poker, flight);
    }

    @Test
    void unsubscribeRoutesEveryAffectedRoomWithoutTreatingTheWholeTabAsDisconnected() {
        UUID pokerId = UUID.randomUUID();
        UUID flightId = UUID.randomUUID();
        when(scheduler.unsubscribe("multi-room-tab", "specific-state")).thenReturn(Set.of(pokerId, flightId));
        when(pokerRooms.find(pokerId)).thenReturn(Optional.of(mock(PokerRoom.class)));
        when(pokerRooms.find(flightId)).thenReturn(Optional.empty());

        presence.unsubscribed(new SessionUnsubscribeEvent(this,
                message(StompCommand.UNSUBSCRIBE, "multi-room-tab", "specific-state", null, human), human));

        verify(scheduler).unsubscribe("multi-room-tab", "specific-state");
        verify(scheduler, never()).unsubscribe("multi-room-tab", null);
        verify(poker).resumeBots(pokerId);
        verify(flight).resumeBots(flightId);
    }

    @Test
    void disconnectClearsTheEventSessionAndDuplicateDisconnectsDoNotResumeRoomsAgain() {
        UUID pokerId = UUID.randomUUID();
        UUID flightId = UUID.randomUUID();
        when(scheduler.unsubscribe("closed-tab", null)).thenReturn(Set.of(pokerId, flightId), Set.of());
        when(pokerRooms.find(pokerId)).thenReturn(Optional.of(mock(PokerRoom.class)));
        when(pokerRooms.find(flightId)).thenReturn(Optional.empty());
        SessionDisconnectEvent event = new SessionDisconnectEvent(this,
                message(StompCommand.DISCONNECT, "different-header-session", null, null, null),
                "closed-tab", CloseStatus.NORMAL, null);

        presence.disconnected(event);
        presence.disconnected(event);

        verify(scheduler, times(2)).unsubscribe("closed-tab", null);
        verify(scheduler, never()).unsubscribe("different-header-session", null);
        verify(poker).resumeBots(pokerId);
        verify(flight).resumeBots(flightId);
    }

    @Test
    void unknownUnsubscriptionDoesNotResumeAnyRoom() {
        when(scheduler.unsubscribe("unknown-tab", "unknown-state")).thenReturn(Set.of());

        presence.unsubscribed(new SessionUnsubscribeEvent(this,
                message(StompCommand.UNSUBSCRIBE, "unknown-tab", "unknown-state", null, human), human));

        verify(scheduler).unsubscribe("unknown-tab", "unknown-state");
        verifyNoInteractions(pokerRooms, flightRooms, poker, flight);
    }

    private static String destination(GameType type, UUID id) {
        return "/user/queue/" + (type == GameType.FLIGHT ? "flight-" : "") + "rooms/" + id;
    }

    private static Message<byte[]> message(StompCommand command, String session, String subscription,
                                           String destination, Principal user) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        headers.setSessionId(session);
        if (subscription != null) headers.setSubscriptionId(subscription);
        if (destination != null) headers.setDestination(destination);
        if (user != null) headers.setUser(user);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
