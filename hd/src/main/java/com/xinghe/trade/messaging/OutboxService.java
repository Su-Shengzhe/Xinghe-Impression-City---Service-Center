package com.xinghe.trade.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OutboxService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public String record(String eventType, String routingKey, Object payload) {
        return record(eventType, routingKey, payload, LocalDateTime.now());
    }

    public String record(String eventType, String routingKey, Object payload, LocalDateTime availableAt) {
        try {
            String eventId = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("INSERT INTO message_outbox (event_id,event_type,routing_key,payload,available_at,status,attempts,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING',0,?,?)",
                    eventId, eventType, routingKey, objectMapper.writeValueAsString(payload), availableAt, now, now);
            return eventId;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("消息内容无法序列化", exception);
        }
    }
}