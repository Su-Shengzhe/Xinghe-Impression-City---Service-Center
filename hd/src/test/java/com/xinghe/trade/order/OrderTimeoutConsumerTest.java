package com.xinghe.trade.order;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import com.xinghe.trade.inventory.ProductInventoryMapper;
import com.xinghe.trade.inventory.ProductInventory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderTimeoutConsumerTest {
    @Test
    void releasesLockedStockWithTheCurrentInventoryVersion() {
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrderItemMapper orderItemMapper = mock(TradeOrderItemMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        TradeOrder order = pendingOrder();
        ProductInventory inventory = new ProductInventory();
        inventory.setSkuId(9L);
        inventory.setLockedQuantity(2);
        inventory.setVersion(7L);
        when(orderMapper.selectById("XH12345678")).thenReturn(order);
        when(orderMapper.update(any(TradeOrder.class), any())).thenReturn(1);
        when(orderItemMapper.selectList(any())).thenReturn(java.util.List.of());
        when(inventoryMapper.selectInventory(9L)).thenReturn(inventory);
        when(inventoryMapper.releaseLocked(9L, 2, 7L)).thenReturn(1);

        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(orderMapper, orderItemMapper, inventoryMapper);
        consumer.cancel("XH12345678");

        verify(inventoryMapper).releaseLocked(9L, 2, 7L);
        verify(orderMapper).update(any(TradeOrder.class), any());
    }

    @Test
    void restoresReservedStockOnlyWhenPendingOrderIsCancelled() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        TradeOrder order = pendingOrder();
        when(orderMapper.selectById("XH12345678")).thenReturn(order);
        when(orderMapper.update(any(TradeOrder.class), any())).thenReturn(1);
        when(inventoryMapper.releaseLocked(9L, 2)).thenReturn(1);

        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(redis, orderMapper, inventoryMapper);
        consumer.cancel("XH12345678");

        verify(inventoryMapper).releaseLocked(9L, 2);
        verifyNoInteractions(redis);
    }

    @Test
    void doesNotRestoreStockWhenTimeoutMessageIsDuplicated() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        TradeOrder order = pendingOrder();
        when(orderMapper.selectById("XH12345678")).thenReturn(order);
        when(orderMapper.update(any(TradeOrder.class), any())).thenReturn(1, 0);
        when(inventoryMapper.releaseLocked(9L, 2)).thenReturn(1);

        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(redis, orderMapper, inventoryMapper);
        consumer.cancel("XH12345678");
        consumer.cancel("XH12345678");

        verify(inventoryMapper, times(1)).releaseLocked(9L, 2);
    }

    @Test
    void doesNotReleaseInventoryWhenCancellationDidNotWinRace() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        TradeOrder pending = pendingOrder();
        TradeOrder cancelled = pendingOrder();
        cancelled.setStatus("CANCELLED");
        when(orderMapper.selectById("XH12345678")).thenReturn(pending, cancelled);
        when(orderMapper.update(any(TradeOrder.class), any())).thenReturn(1, 0);
        when(inventoryMapper.releaseLocked(9L, 2)).thenReturn(1);

        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(redis, orderMapper, inventoryMapper);
        consumer.cancel("XH12345678");

        verify(inventoryMapper, times(1)).releaseLocked(9L, 2);
    }

    private TradeOrder pendingOrder() {
        TradeOrder order = new TradeOrder();
        order.setOrderNo("XH12345678");
        order.setSkuId(9L);
        order.setQuantity(2);
        order.setStatus("PENDING_PAYMENT");
        return order;
    }
}
