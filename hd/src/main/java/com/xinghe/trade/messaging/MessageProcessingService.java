package com.xinghe.trade.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MessageProcessingService {
    private final JdbcTemplate jdbc;

    public MessageProcessingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean processOnce(String eventId, String eventType, Runnable work) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("消息缺少 eventId");
        int inserted = jdbc.update("INSERT IGNORE INTO message_processing (event_id,event_type,processed_at) VALUES (?,?,?)",
                eventId, eventType, LocalDateTime.now());
        if (inserted == 0) return false;
        work.run();
        return true;
    }
}