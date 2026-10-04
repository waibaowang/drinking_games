package cn.friendpoker.server.flight;

import cn.friendpoker.domain.game.GameRuleException;
import cn.friendpoker.server.room.GameWebSocketController.SocketError;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@Controller
public class FlightWebSocketController {
    private static final Logger log = LoggerFactory.getLogger(FlightWebSocketController.class);
    private final FlightCoordinator games;
    private final UserAccountRepository users;

    public FlightWebSocketController(FlightCoordinator games, UserAccountRepository users) {
        this.games = games;
        this.users = users;
    }

    @MessageMapping("/flight/rooms/{roomId}/action")
    public void action(@DestinationVariable UUID roomId, @Payload ActionMessage message, Principal principal) {
        try {
            games.act(roomId, principal.getName(), message.actionId(), message.action(), message.pieceIndex(),
                    message.version());
        } catch (RuntimeException exception) {
            log.info("room={} user={} actionId={} operation=FLIGHT_{} rejected reason={}", roomId,
                    principal.getName(), message.actionId(), message.action(), exception.getMessage());
            throw exception;
        }
    }

    @MessageMapping("/flight/rooms/{roomId}/chat")
    public void chat(@DestinationVariable UUID roomId, @Payload ChatRequest message, Principal principal) {
        UserAccount account = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        games.chat(roomId, principal.getName(), account.getDisplayName(), message.text());
    }

    @MessageExceptionHandler({GameRuleException.class, IllegalArgumentException.class})
    @SendToUser("/queue/errors")
    public SocketError error(RuntimeException exception) {
        return new SocketError(exception.getMessage(), Instant.now());
    }

    public record ActionMessage(String actionId, String action, Integer pieceIndex, long version) {}
    public record ChatRequest(String text) {}
}
