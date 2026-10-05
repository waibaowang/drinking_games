package cn.friendpoker.server.room;

import cn.friendpoker.domain.game.TableConfig;
import cn.friendpoker.domain.game.TableSnapshot;
import cn.friendpoker.server.bot.BotSeat;
import cn.friendpoker.server.bot.BotSettings;
import java.util.List;

import java.time.Instant;
import java.util.UUID;

public record RoomState(
        UUID id,
        GameType gameType,
        String code,
        String name,
        String hostUsername,
        TableConfig config,
        TableSnapshot table,
        Instant actionDeadline,
        RoomStatus status,
        boolean recovered,
        List<BotSeat> bots,
        BotSettings botSettings,
        long stateVersion
) {}
