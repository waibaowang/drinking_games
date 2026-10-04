package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.TableConfig;
import cn.friendpoker.domain.game.TableSnapshot;

import java.time.Instant;
import java.util.UUID;

public record RoomState(
        UUID id,
        String code,
        String name,
        String hostUsername,
        TableConfig config,
        TableSnapshot table,
        Instant actionDeadline
) {}

