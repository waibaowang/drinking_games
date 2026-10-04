package cn.friendpoker.server.flight;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/rooms/flight")
public class FlightRoomController {
    private final FlightRoomService rooms;
    private final FlightCoordinator games;

    public FlightRoomController(FlightRoomService rooms, FlightCoordinator games) {
        this.rooms = rooms;
        this.games = games;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlightRoomState create(@Valid @RequestBody CreateFlightRoomRequest request, Principal principal) {
        FlightRoom room = rooms.create(principal.getName(), request);
        return games.state(room.id(), principal.getName());
    }
}
