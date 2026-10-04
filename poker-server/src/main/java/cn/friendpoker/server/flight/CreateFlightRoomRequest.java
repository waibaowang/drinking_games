package cn.friendpoker.server.flight;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateFlightRoomRequest(
        @NotBlank @Size(max = 30) String name,
        @Size(max = 32) String password
) {
    public CreateFlightRoomRequest {
        password = password == null ? "" : password;
    }
}
