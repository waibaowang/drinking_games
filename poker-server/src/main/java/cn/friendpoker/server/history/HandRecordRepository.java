package cn.friendpoker.server.history;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HandRecordRepository extends JpaRepository<HandRecord, String> {
}

