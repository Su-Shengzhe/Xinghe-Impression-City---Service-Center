package com.xinghe.trade.messaging;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.xinghe.trade.config.MessagingConfig.EVENT_EXCHANGE;

@Component
@ConditionalOnProperty(name = "xinghe.messaging.enabled", havingValue = "true")
public class OutboxPublisher {
    private static final int MAX_ATTEMPTS = 10;
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${xinghe.messaging.outbox-poll-ms:1000}")
    public void publishPending() {
        jdbc.update("UPDATE message_outbox SET status='PENDING',updated_at=? WHERE status='PUBLISHING' AND updated_at<?",
                LocalDateTime.now(), LocalDateTime.now().minusMinutes(5));
        List<OutboxEvent> events = jdbc.query("SELECT event_id,event_type,routing_key,payload,available_at,attempts FROM message_outbox WHERE status='PENDING' AND available_at<=? ORDER BY created_at LIMIT 50",
                (rs, row) -> new OutboxEvent(rs.getString("event_id"), rs.getString("event_type"),
                        rs.getString("routing_key"), rs.getString("payload"),
                        rs.getTimestamp("available_at").toLocalDateTime(), rs.getInt("attempts")), LocalDateTime.now());
        for (OutboxEvent event : events) publish(event);
    }

    private void publish(OutboxEvent event) {
        int claimed = jdbc.update("UPDATE message_outbox SET status='PUBLISHING',updated_at=? WHERE event_id=? AND status='PENDING'",
                LocalDateTime.now(), event.eventId());
        if (claimed != 1) return;
        try {
            CorrelationData correlation = new CorrelationData(event.eventId());
            rabbit.convertAndSend(EVENT_EXCHANGE, event.routingKey(), event.payload(), message -> {
                message.getMessageProperties().setMessageId(event.eventId());
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                if ("order.timeout".equals(event.routingKey())) {
                    java.util.regex.Matcher expiry = java.util.regex.Pattern.compile("\\\"expiresAt\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(event.payload());
                    long delay = expiry.find()
                            ? Math.max(1L, java.time.Duration.between(LocalDateTime.now(), LocalDateTime.parse(expiry.group(1))).toMillis())
                            : 900_000L;
                    message.getMessageProperties().setExpiration(Long.toString(delay));
                }
                return message;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) throw new IllegalStateException("RabbitMQ publisher nack: " + confirm.getReason());
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned unroutable message: " +
                        correlation.getReturned().getReplyText());
            }
            jdbc.update("UPDATE message_outbox SET status='PUBLISHED',last_error=NULL,updated_at=? WHERE event_id=? AND status='PUBLISHING'",
                    LocalDateTime.now(), event.eventId());
        } catch (Exception exception) {
            int attempts = event.attempts() + 1;
            String status = attempts >= MAX_ATTEMPTS ? "DEAD" : "PENDING";
            long delaySeconds = Math.min(300L, 1L << Math.min(attempts, 8));
            jdbc.update("UPDATE message_outbox SET status=?,attempts=?,available_at=?,last_error=?,updated_at=? WHERE event_id=? AND status='PUBLISHING'",
                    status, attempts, LocalDateTime.now().plusSeconds(delaySeconds), truncate(exception.toString()),
                    LocalDateTime.now(), event.eventId());
        }
    }

    private String truncate(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}