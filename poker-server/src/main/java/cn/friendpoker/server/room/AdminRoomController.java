package cn.friendpoker.server.room;

import cn.friendpoker.server.flight.FlightCoordinator;
import cn.friendpoker.server.flight.FlightRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/rooms")
public class AdminRoomController {
    private final RoomService rooms;
    private final GameCoordinator games;
    private final FlightRoomService flightRooms;
    private final FlightCoordinator flightGames;

    public AdminRoomController(RoomService rooms, GameCoordinator games, FlightRoomService flightRooms,
                               FlightCoordinator flightGames) {
        this.rooms = rooms;
        this.games = games;
        this.flightRooms = flightRooms;
        this.flightGames = flightGames;
    }

    @GetMapping
    public List<RoomSummary> list() {
        List<RoomSummary> result = new ArrayList<>(rooms.activeRooms());
        result.addAll(flightRooms.activeRooms());
        return result;
    }

    @PostMapping("/{roomId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable UUID roomId, Principal principal) {
        if (rooms.find(roomId).isPresent()) games.close(roomId, principal.getName());
        else flightGames.close(roomId, principal.getName());
    }
}
