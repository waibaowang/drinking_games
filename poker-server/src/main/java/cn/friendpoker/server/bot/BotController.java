package cn.friendpoker.server.bot;

import cn.friendpoker.server.flight.FlightCoordinator;
import cn.friendpoker.server.room.GameCoordinator;
import cn.friendpoker.server.room.RoomService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms/{roomId}/bots")
public class BotController {
    private final RoomService rooms;
    private final GameCoordinator poker;
    private final FlightCoordinator flight;

    public BotController(RoomService rooms, GameCoordinator poker, FlightCoordinator flight) {
        this.rooms = rooms; this.poker = poker; this.flight = flight;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@PathVariable UUID roomId, @Valid @RequestBody AddBot body, Principal user) {
        if (body.seat() == null) throw new IllegalArgumentException("请选择机器人座位");
        add(roomId, user.getName(), body.seat());
    }

    @PostMapping("/fill")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fill(@PathVariable UUID roomId, Principal user) { add(roomId, user.getName(), null); }

    private void add(UUID id, String user, Integer seat) {
        if (rooms.find(id).isPresent()) poker.addBots(id, user, seat);
        else flight.addBots(id, user, seat);
    }

    @DeleteMapping("/{botId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID roomId, @PathVariable String botId, Principal user) {
        if (rooms.find(roomId).isPresent()) poker.removeBot(roomId, user.getName(), botId);
        else flight.removeBot(roomId, user.getName(), botId);
    }

    @PatchMapping("/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void settings(@PathVariable UUID roomId, @Valid @RequestBody Settings body, Principal user) {
        if (rooms.find(roomId).isPresent()) poker.botSettings(roomId, user.getName(), body.enabled(), body.speed());
        else flight.botSettings(roomId, user.getName(), body.enabled(), body.speed());
    }

    public record AddBot(@Min(0) @Max(8) Integer seat) {}
    public record Settings(Boolean enabled, BotSettings.Speed speed) {}
}
