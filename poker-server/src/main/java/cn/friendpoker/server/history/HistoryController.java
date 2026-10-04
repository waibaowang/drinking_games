package cn.friendpoker.server.history;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final JdbcTemplate jdbc;

    public HistoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public HistoryPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                            Principal principal) {
        Long total = jdbc.queryForObject("select count(*) from hand_record_players where username = ?",
                Long.class, principal.getName());
        List<HistoryItem> items = jdbc.query("""
                select h.id, h.room_id, h.room_name, h.hand_number, h.summary, h.completed_at, h.game_type
                from hand_records h join hand_record_players p on p.hand_id = h.id
                where p.username = ? order by h.completed_at desc limit ? offset ?
                """, this::map, principal.getName(), size, page * size);
        long count = total == null ? 0 : total;
        int pages = count == 0 ? 0 : (int) ((count + size - 1) / size);
        return new HistoryPage(items, page, size, count, pages);
    }

    @GetMapping("/{handId}")
    public HistoryItem detail(@PathVariable String handId, Principal principal) {
        List<HistoryItem> records = jdbc.query("""
                select h.id, h.room_id, h.room_name, h.hand_number, h.summary, h.completed_at, h.game_type
                from hand_records h join hand_record_players p on p.hand_id = h.id
                where h.id = ? and p.username = ?
                """, this::map, handId, principal.getName());
        if (records.isEmpty()) throw new IllegalArgumentException("牌局记录不存在");
        return records.getFirst();
    }

    private HistoryItem map(ResultSet rs, int row) throws SQLException {
        return new HistoryItem(rs.getString("id"), rs.getString("room_id"), rs.getString("room_name"),
                rs.getLong("hand_number"), rs.getString("summary"),
                rs.getTimestamp("completed_at").toInstant(), rs.getString("game_type"));
    }

    public record HistoryItem(String id, String roomId, String roomName, long handNumber,
                              String summary, Instant completedAt, String gameType) {}
    public record HistoryPage(List<HistoryItem> items, int page, int size, long totalElements, int totalPages) {}
}
