package com.xinghe.trade.order;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalDateTime;

import com.xinghe.trade.inventory.ProductInventoryMapper;

import static org.mockito.Mockito.*;

class OrderExpirationSchedulerTest {
    @Test
    void continuesWithOtherExpiredOrdersAfterOneFailure() {
        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        OrderTimeoutConsumer cancellation = new OrderTimeoutConsumer(orders,
                mock(TradeOrderItemMapper.class), mock(ProductInventoryMapper.class));
        TradeOrder broken = expired("broken");
        TradeOrder ready = expired("ready");
        when(orders.selectExpiredOrders(null, null, 100)).thenReturn(List.of(broken, ready));
        when(orders.selectById("broken")).thenThrow(new IllegalStateException("inventory unavailable"));

        new OrderExpirationScheduler(orders, cancellation).cancelExpired();

        verify(orders).selectById("broken");
        verify(orders).selectById("ready");
    }

    private TradeOrder expired(String orderNo) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo(orderNo);
        order.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        return order;
    }
}
