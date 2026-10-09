package com.xinghe.trade.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MessageProcessingServiceTest {
    @Test
    void skipsDuplicateEventBeforeRunningBusinessWork() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
        MessageProcessingService service = new MessageProcessingService(jdbc);
        Runnable work = mock(Runnable.class);

        assertThat(service.processOnce("event-1", "PRODUCT_INDEX", work)).isFalse();
        verifyNoInteractions(work);
    }

    @Test
    void runsBusinessWorkForFirstEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
        MessageProcessingService service = new MessageProcessingService(jdbc);
        Runnable work = mock(Runnable.class);

        assertThat(service.processOnce("event-1", "PRODUCT_INDEX", work)).isTrue();
        verify(work).run();
    }
}