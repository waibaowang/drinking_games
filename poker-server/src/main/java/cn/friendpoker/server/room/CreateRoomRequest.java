package cn.friendpoker.server.room;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank @Size(max = 30) String name,
        @Size(max = 32) String password,
        @Min(1) long smallBlind,
        @Min(2) long bigBlind,
        @Min(20) long initialStack,
        @Min(2) @Max(9) int maxPlayers,
        @Min(10) @Max(120) int actionSeconds
) {
    public CreateRoomRequest {
        password = password == null ? "" : password;
    }
}

