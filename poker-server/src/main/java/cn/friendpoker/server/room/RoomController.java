package cn.friendpoker.server.room;

import cn.friendpoker.server.user.UserAccount;
import cn.friendpoker.server.user.UserAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    private final RoomService rooms;
    private final GameCoordinator games;
    private final UserAccountRepository users;

    public RoomController(RoomService rooms, GameCoordinator games, UserAccountRepository users) {
        this.rooms = rooms;
        this.games = games;
        this.users = users;
    }

    @GetMapping("/mine")
    public List<RoomSummary> mine(Principal principal) {
        return rooms.mine(principal.getName());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomState create(@Valid @RequestBody CreateRoomRequest request, Principal principal) {
        PokerRoom room = rooms.create(principal.getName(), request);
        return games.state(room.id(), principal.getName());
    }

    @PostMapping("/join")
    public RoomState joinByCode(@Valid @RequestBody JoinByCodeRequest request, Principal principal) {
        PokerRoom room = rooms.joinByCode(request.code(), principal.getName(), request.password());
        games.broadcast(room.id());
        return games.state(room.id(), principal.getName());
    }

    @GetMapping("/{roomId}")
    public RoomState state(@PathVariable UUID roomId, Principal principal) {
        return games.state(roomId, principal.getName());
    }

    @PostMapping("/{roomId}/seats/{seat}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sit(@PathVariable UUID roomId, @PathVariable @Min(0) @Max(8) int seat, Principal principal) {
        UserAccount account = users.findByUsernameIgnoreCase(principal.getName()).orElseThrow();
        games.sit(roomId, principal.getName(), account.getDisplayName(), seat);
    }

    @DeleteMapping("/{roomId}/seat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stand(@PathVariable UUID roomId, Principal principal) {
        games.stand(roomId, principal.getName());
    }

    @PostMapping("/{roomId}/start")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void start(@PathVariable UUID roomId, Principal principal) {
        games.start(roomId, principal.getName());
    }

    public record JoinByCodeRequest(@NotBlank @Size(min = 6, max = 6) String code, String password) {
        public JoinByCodeRequest { password = password == null ? "" : password; }
    }
}
