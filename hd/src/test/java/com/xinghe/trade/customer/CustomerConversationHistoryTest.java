package com.xinghe.trade.customer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghe.trade.logistics.LogisticsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerConversationHistoryTest {
    @Test
    void historyReturnsTheBoundedPageInChronologicalOrder() {
        CustomerServiceSessionMapper sessions = mock(CustomerServiceSessionMapper.class);
        CustomerServiceMessageMapper messages = mock(CustomerServiceMessageMapper.class);
        CustomerServiceSession session = new CustomerServiceSession();
        session.setSessionId("session-1"); session.setUserId("user-1");
        when(sessions.selectById("session-1")).thenReturn(session);
        CustomerServiceMessage older = message("a", "session-1", LocalDateTime.parse("2026-10-13T12:00:00"));
        CustomerServiceMessage newer = message("b", "session-1", LocalDateTime.parse("2026-10-13T12:01:00"));
        when(messages.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(newer, older));

        List<CustomerConversationService.MessageView> page = service(sessions, messages).history("user-1", "session-1");

        assertThat(page).extracting(CustomerConversationService.MessageView::messageId).containsExactly("a", "b");
        verify(messages, times(1)).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void rejectsCursorBelongingToAnotherSession() {
        CustomerServiceSessionMapper sessions = mock(CustomerServiceSessionMapper.class);
        CustomerServiceMessageMapper messages = mock(CustomerServiceMessageMapper.class);
        CustomerServiceSession session = new CustomerServiceSession();
        session.setSessionId("session-1"); session.setUserId("user-1");
        when(sessions.selectById("session-1")).thenReturn(session);
        when(messages.selectById("foreign")).thenReturn(message("foreign", "session-2", LocalDateTime.now()));

        assertThatThrownBy(() -> service(sessions, messages).history("user-1", "session-1", "foreign"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("历史游标无效");
        verify(messages, never()).selectList(any(LambdaQueryWrapper.class));
    }

    private static CustomerConversationService service(CustomerServiceSessionMapper sessions,
                                                       CustomerServiceMessageMapper messages) {
        return new CustomerConversationService(sessions, messages, mock(StringRedisTemplate.class),
                mock(FaqService.class), mock(OrderQueryService.class), mock(CustomerAgentService.class),
                mock(LogisticsQueryService.class), mock(CustomerConversationPersistence.class));
    }

    private static CustomerServiceMessage message(String id, String sessionId, LocalDateTime createdAt) {
        CustomerServiceMessage value = new CustomerServiceMessage();
        value.setMessageId(id); value.setSessionId(sessionId); value.setCreatedAt(createdAt);
        value.setMessageType("USER"); value.setContent("hello");
        return value;
    }
}
