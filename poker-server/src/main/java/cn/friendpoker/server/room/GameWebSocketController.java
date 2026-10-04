package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.ActionType;
import cn.friendpoker.domain.game.GameRuleException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@Controller
public class GameWebSocketController {
    private final GameCoordinator games;
    private final UserAccountRepository users;

    public GameWebSocketController(GameCoordinator games, UserAccountRepository users) {
        this.games = games;
        this.users = users;
    }

    @MessageMapping("/rooms/{roomId}/action")
    public void action(@DestinationVariable UUID roomId, @Payload ActionMessage message, Principal principal) {
        if (message.action() == null) throw new IllegalArgumentException("缺少操作类型");
        games.act(roomId, principal.getName(), message.actionId(), message.action(), message.amount(), message.version());
    }

    @MessageMapping("/rooms/{roomId}/chat")
    public void chat(@DestinationVariable UUID roomId, @Payload ChatRequest message, Principal principal) {
        UserAccount account = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        games.chat(roomId, principal.getName(), account.getDisplayName(), message.text());
    }

    @MessageExceptionHandler({GameRuleException.class, IllegalArgumentException.class})
    @SendToUser("/queue/errors")
    public SocketError error(RuntimeException exception) {
        return new SocketError(exception.getMessage(), Instant.now());
    }

    public record ActionMessage(String actionId, ActionType action, long amount, long version) {}
    public record ChatRequest(String text) {}
    public record SocketError(String message, Instant timestamp) {}
}
