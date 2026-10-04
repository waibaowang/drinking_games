package cn.friendpoker.server.room;

import java.util.UUID;

public record RoomSummary(
        UUID id, String code, String name, boolean locked, int members, int maxPlayers, long smallBlind, long bigBlind,
        RoomStatus status, boolean recovered
) {}
