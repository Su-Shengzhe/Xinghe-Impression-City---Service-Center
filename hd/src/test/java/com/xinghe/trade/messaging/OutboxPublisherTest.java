package com.xinghe.trade.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {
    @Test
    void putsEventBackToPendingAndIncrementsAttemptsAfterPublisherNack() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEvent event = eventWithAttempts(0);
        when(jdbc.query(anyString(), anyRowMapper(), any(LocalDateTime.class))).thenReturn(List.of(event));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        nack(rabbit, "exchange unavailable");

        new OutboxPublisher(jdbc, rabbit).publishPending();

        verify(jdbc).update(
                eq("UPDATE message_outbox SET status=?,attempts=?,available_at=?,last_error=?,updated_at=? WHERE event_id=? AND status='PUBLISHING'"),
                eq("PENDING"), eq(1), any(LocalDateTime.class),
                eq("java.lang.IllegalStateException: RabbitMQ publisher nack: exchange unavailable"),
                any(LocalDateTime.class), eq("event-1"));
    }

    @Test
    void marksEventDeadAfterTenthPublishFailure() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxEvent event = eventWithAttempts(9);
        when(jdbc.query(anyString(), anyRowMapper(), any(LocalDateTime.class))).thenReturn(List.of(event));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        nack(rabbit, "exchange unavailable");

        new OutboxPublisher(jdbc, rabbit).publishPending();

        verify(jdbc).update(
                eq("UPDATE message_outbox SET status=?,attempts=?,available_at=?,last_error=?,updated_at=? WHERE event_id=? AND status='PUBLISHING'"),
                eq("DEAD"), eq(10), any(LocalDateTime.class),
                eq("java.lang.IllegalStateException: RabbitMQ publisher nack: exchange unavailable"),
                any(LocalDateTime.class), eq("event-1"));
    }

    @SuppressWarnings("unchecked")
    private RowMapper<OutboxEvent> anyRowMapper() {
        return (RowMapper<OutboxEvent>) any(RowMapper.class);
    }

    private void nack(RabbitTemplate rabbit, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            assertThat(correlation.getId()).isEqualTo("event-1");
            correlation.getFuture().complete(new CorrelationData.Confirm(false, reason));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString(), any(), any(CorrelationData.class));
    }

    private OutboxEvent eventWithAttempts(int attempts) {
        return new OutboxEvent("event-1", "PRODUCT_INDEX", "product.index", "{}",
                LocalDateTime.now(), attempts);
    }
}