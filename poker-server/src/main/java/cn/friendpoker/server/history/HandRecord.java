package cn.friendpoker.server.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hand_records")
public class HandRecord {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;
    @Column(name = "hand_number", nullable = false)
    private long handNumber;
    @Column(name = "summary", nullable = false, columnDefinition = "text")
    private String summary;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected HandRecord() {}

    public HandRecord(UUID roomId, long handNumber, String summary) {
        this.id = UUID.randomUUID().toString();
        this.roomId = roomId.toString();
        this.handNumber = handNumber;
        this.summary = summary;
        this.completedAt = Instant.now();
    }
}

