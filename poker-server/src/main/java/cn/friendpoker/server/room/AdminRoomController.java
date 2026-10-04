package cn.friendpoker.server.room;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/rooms")
public class AdminRoomController {
    private final RoomService rooms;
    private final GameCoordinator games;

    public AdminRoomController(RoomService rooms, GameCoordinator games) {
        this.rooms = rooms;
        this.games = games;
    }

    @GetMapping
    public List<RoomSummary> list() {
        return rooms.activeRooms();
    }

    @PostMapping("/{roomId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable UUID roomId, Principal principal) {
        games.close(roomId, principal.getName());
    }
}
