package cn.friendpoker.server.bot;

import cn.friendpoker.server.flight.FlightCoordinator;
import cn.friendpoker.server.flight.FlightRoomService;
import cn.friendpoker.server.room.GameCoordinator;
import cn.friendpoker.server.room.RoomService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class BotPresence {
    private static final Pattern STATE_TOPIC = Pattern.compile("^/user/queue/(flight-)?rooms/([0-9a-fA-F-]{36})$");
    private final BotScheduler scheduler;
    private final RoomService rooms;
    private final FlightRoomService flightRooms;
    private final GameCoordinator poker;
    private final FlightCoordinator flight;

    public BotPresence(BotScheduler scheduler, RoomService rooms, FlightRoomService flightRooms,
                       GameCoordinator poker, FlightCoordinator flight) {
        this.scheduler = scheduler; this.rooms = rooms; this.flightRooms = flightRooms;
        this.poker = poker; this.flight = flight;
    }

    @EventListener
    public void subscribed(SessionSubscribeEvent event) {
        var headers = StompHeaderAccessor.wrap(event.getMessage());
        if (event.getUser() == null || headers.getDestination() == null) return;
        var match = STATE_TOPIC.matcher(headers.getDestination());
        if (!match.matches()) return;
        try {
            UUID id = UUID.fromString(match.group(2));
            if (match.group(1) == null) rooms.requireMember(id, event.getUser().getName());
            else flightRooms.requireMember(id, event.getUser().getName());
            scheduler.subscribe(headers.getSessionId(), headers.getSubscriptionId(), id, event.getUser());
            resume(id);
        } catch (IllegalArgumentException ignored) { /* A foreign private queue is not room presence. */ }
    }

    @EventListener
    public void unsubscribed(SessionUnsubscribeEvent event) {
        var headers = StompHeaderAccessor.wrap(event.getMessage());
        scheduler.unsubscribe(headers.getSessionId(), headers.getSubscriptionId()).forEach(this::resume);
    }

    @EventListener
    public void disconnected(SessionDisconnectEvent event) {
        scheduler.unsubscribe(event.getSessionId(), null).forEach(this::resume);
    }

    private void resume(UUID id) {
        if (rooms.find(id).isPresent()) poker.resumeBots(id);
        else flight.resumeBots(id);
    }
}
