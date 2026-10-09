package com.xinghe.trade.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxServiceTest {
    @Test
    void recordsJsonPayloadAndAvailabilityInSameJdbcTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        OutboxService service = new OutboxService(jdbc, new ObjectMapper());
        LocalDateTime availableAt = LocalDateTime.now().plusMinutes(15);

        String eventId = service.record("ORDER_TIMEOUT", "order.timeout", Map.of("orderNo", "XH-1"), availableAt);

        assertThat(eventId).isNotBlank();
        verify(jdbc).update(contains("INSERT INTO message_outbox"), eq(eventId), eq("ORDER_TIMEOUT"),
                eq("order.timeout"), contains("orderNo"), eq(availableAt), any(), any());
    }
}