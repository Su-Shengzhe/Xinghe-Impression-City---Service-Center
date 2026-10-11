package com.xinghe.trade.order;

import com.xinghe.trade.product.Product;
import com.xinghe.trade.product.ProductMapper;
import com.xinghe.trade.cart.CartItem;
import com.xinghe.trade.cart.CartItemMapper;
import com.xinghe.trade.inventory.ProductInventoryMapper;
import com.xinghe.trade.inventory.ProductInventory;
import com.xinghe.trade.logistics.LogisticsMapper;
import com.xinghe.trade.logistics.LogisticsRecord;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    @Test
    void checksOutCartUsingServerPriceAndLocksDatabaseInventory() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrderItemMapper itemMapper = mock(TradeOrderItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        CartItem cartItem = new CartItem();
        cartItem.setId(1L);
        cartItem.setUserId("user-1");
        cartItem.setSkuId(10001L);
        cartItem.setQuantity(2);
        when(cartMapper.selectForCheckout("user-1")).thenReturn(List.of(cartItem));
        when(productMapper.selectById(10001L)).thenReturn(product("ON_SALE", "199.00"));
        when(orderMapper.updateById(any(TradeOrder.class))).thenReturn(1);
        when(inventoryMapper.lockAvailable(10001L, 2)).thenReturn(1);
        when(orderMapper.insert(any(TradeOrder.class))).thenReturn(1);
        when(orderMapper.updateById(any(TradeOrder.class))).thenReturn(1);
        when(itemMapper.insert(any(TradeOrderItem.class))).thenReturn(1);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class))).thenReturn(true);
        when(cartMapper.deleteSnapshot(eq("user-1"), anyList())).thenReturn(1);
        OrderService service = new OrderService(redis, mock(RabbitTemplate.class),
                orderMapper, itemMapper, productMapper, cartMapper, inventoryMapper);

        List<OrderService.OrderResult> results = service.checkout(" user-1 ");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).status()).isEqualTo("PENDING_PAYMENT");
        assertThat(results.get(1).status()).isEqualTo("PENDING_PAYMENT");
        verify(orderMapper).insert(org.mockito.ArgumentMatchers.<TradeOrder>argThat(order -> order.getUserId().equals("user-1") &&
                order.getSkuId() == null && order.getAmount().compareTo(new BigDecimal("398.00")) == 0 &&
                order.getParentOrderNo() == null));
        verify(orderMapper).insert(org.mockito.ArgumentMatchers.<TradeOrder>argThat(order -> Long.valueOf(1L).equals(order.getStoreId()) &&
                order.getAmount().compareTo(new BigDecimal("398.00")) == 0 && order.getParentOrderNo() != null));
        verify(itemMapper).insert(org.mockito.ArgumentMatchers.<TradeOrderItem>argThat(item -> item.getQuantity() == 2 &&
                item.getUnitPrice().compareTo(new BigDecimal("199.00")) == 0));
        verify(cartMapper).deleteSnapshot("user-1", List.of(1L));
    }

    @Test
    void doesNotClearCartWhenInventoryCannotBeLocked() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        CartItem cartItem = new CartItem();
        cartItem.setId(2L);
        cartItem.setSkuId(10001L);
        cartItem.setQuantity(1);
        when(cartMapper.selectForCheckout("user-1")).thenReturn(List.of(cartItem));
        ProductMapper productMapper = mock(ProductMapper.class);
        when(productMapper.selectById(10001L)).thenReturn(product("ON_SALE", "199.00"));
        when(inventoryMapper.lockAvailable(10001L, 1)).thenReturn(0);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        when(orderMapper.insert(any(TradeOrder.class))).thenReturn(1);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class))).thenReturn(true);
        OrderService service = new OrderService(redis, mock(RabbitTemplate.class),
                orderMapper, mock(TradeOrderItemMapper.class), productMapper,
                cartMapper, inventoryMapper);

        assertThatThrownBy(() -> service.checkout("user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("商品库存不足");
        verify(cartMapper, never()).deleteSnapshot(anyString(), anyList());
    }

    /** 确认同店多个 SKU 聚合为一个子单，不同店铺分别生成子单。 */
    @Test
    void groupsCheckoutItemsIntoOneChildOrderPerStore() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrderItemMapper itemMapper = mock(TradeOrderItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        CartItem first = cartItem(101L, 1);
        CartItem second = cartItem(102L, 2);
        CartItem third = cartItem(201L, 3);
        first.setId(1L);
        second.setId(2L);
        third.setId(3L);
        when(cartMapper.selectForCheckout("user-1")).thenReturn(List.of(first, second, third));
        when(productMapper.selectById(101L)).thenReturn(product(101L, 1L, "10.00"));
        when(productMapper.selectById(102L)).thenReturn(product(102L, 1L, "5.00"));
        when(productMapper.selectById(201L)).thenReturn(product(201L, 2L, "2.00"));
        when(inventoryMapper.lockAvailable(anyLong(), anyInt())).thenReturn(1);
        when(orderMapper.insert(any(TradeOrder.class))).thenReturn(1);
        when(orderMapper.updateById(any(TradeOrder.class))).thenReturn(1);
        when(itemMapper.insert(any(TradeOrderItem.class))).thenReturn(1);
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class))).thenReturn(true);
        when(cartMapper.deleteSnapshot(eq("user-1"), anyList())).thenReturn(3);

        OrderService service = new OrderService(redis, mock(RabbitTemplate.class),
                orderMapper, itemMapper, productMapper, cartMapper, inventoryMapper);
        List<OrderService.OrderResult> results = service.checkout("user-1");

        assertThat(results).hasSize(3);
        verify(orderMapper, times(1)).insert(org.mockito.ArgumentMatchers.<TradeOrder>argThat(order ->
                order.getParentOrderNo() == null && order.getSkuId() == null &&
                        order.getAmount().compareTo(new BigDecimal("26.00")) == 0));
        verify(orderMapper, times(1)).insert(org.mockito.ArgumentMatchers.<TradeOrder>argThat(order ->
                Long.valueOf(1L).equals(order.getStoreId()) && order.getParentOrderNo() != null));
        verify(orderMapper, times(1)).insert(org.mockito.ArgumentMatchers.<TradeOrder>argThat(order ->
                Long.valueOf(2L).equals(order.getStoreId()) && order.getParentOrderNo() != null));
        verify(itemMapper, times(3)).insert(any(TradeOrderItem.class));
    }

    @Test
    void replaysCompletedCheckoutForSameTokenWithoutCreatingAnotherOrder() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductInventoryMapper inventoryMapper = mock(ProductInventoryMapper.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrderItemMapper itemMapper = mock(TradeOrderItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        CartItem cartItem = cartItem(10001L, 1);
        cartItem.setId(9L);
        when(cartMapper.selectForCheckout("user-1")).thenReturn(List.of(cartItem));
        when(productMapper.selectById(10001L)).thenReturn(product("ON_SALE", "199.00"));
        when(inventoryMapper.lockAvailable(10001L, 1)).thenReturn(1);
        when(orderMapper.insert(any(TradeOrder.class))).thenReturn(1);
        when(orderMapper.updateById(any(TradeOrder.class))).thenReturn(1);
        when(itemMapper.insert(any(TradeOrderItem.class))).thenReturn(1);
        TradeOrder parent = new TradeOrder();
        parent.setOrderNo("PARENT-REPLAY");
        parent.setUserId("user-1");
        parent.setStatus("PENDING_PAYMENT");
        when(orderMapper.selectById("PARENT-REPLAY")).thenReturn(parent);
        when(orderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("PROCESSING"), any(Duration.class)))
                .thenReturn(true, false);
        when(values.get(anyString())).thenReturn("PARENT-REPLAY");
        when(cartMapper.deleteSnapshot(eq("user-1"), anyList())).thenReturn(1);

        OrderService service = new OrderService(redis, mock(RabbitTemplate.class), orderMapper, itemMapper,
                productMapper, cartMapper, inventoryMapper);

        service.checkout("user-1", "checkout-replay");
        List<OrderService.OrderResult> replay = service.checkout("user-1", "checkout-replay");

        assertThat(replay).containsExactly(new OrderService.OrderResult("PARENT-REPLAY", "PENDING_PAYMENT"));
        verify(orderMapper, times(2)).insert(any(TradeOrder.class));
        verify(cartMapper, times(1)).selectForCheckout("user-1");
    }

    /** 确认取消父订单时每个子订单明细只释放对应 SKU 的锁定库存。 */
    @Test
    void cancellingParentOrderCancelsChildrenAndReleasesEachSku() {
        TradeOrder parent = new TradeOrder();
        parent.setOrderNo("PARENT-1");
        parent.setUserId("user-1");
        parent.setStatus("PENDING_PAYMENT");
        TradeOrder child = new TradeOrder();
        child.setOrderNo("CHILD-1");
        child.setParentOrderNo("PARENT-1");
        child.setStatus("PENDING_PAYMENT");
        TradeOrderItem item = new TradeOrderItem();
        item.setOrderNo("CHILD-1");
        item.setSkuId(101L);
        item.setQuantity(2);

        TradeOrderMapper orders = mock(TradeOrderMapper.class);
        TradeOrderItemMapper items = mock(TradeOrderItemMapper.class);
        ProductInventoryMapper inventory = mock(ProductInventoryMapper.class);
        when(orders.selectById("PARENT-1")).thenReturn(parent);
        when(orders.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(child));
        when(orders.update(any(TradeOrder.class), any())).thenReturn(1);
        when(items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item));
        when(inventory.releaseLocked(101L, 2)).thenReturn(1);

        OrderService service = new OrderService(mock(RedisTemplate.class), mock(RabbitTemplate.class),
                orders, items, mock(ProductMapper.class), mock(CartItemMapper.class), inventory);
        service.cancel("user-1", "PARENT-1");

        verify(orders).update(any(TradeOrder.class), any());
        verify(inventory).releaseLocked(101L, 2);
    }

    @Test
    void rejectsClientTamperedAmountBeforeReservingStock() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrderItemMapper itemMapper = mock(TradeOrderItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        Product product = product("ON_SALE", "199.00");
        when(productMapper.selectById(10001L)).thenReturn(product);
        OrderService service = new OrderService(redis, rabbit, orderMapper, itemMapper, productMapper);

        // 阶段 A：服务端价格重算必须拒绝客户端篡改订单金额。
        assertThatThrownBy(() -> service.create(new OrderController.CreateOrderRequest(
                "user-1", 10001L, 2, new BigDecimal("1.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订单金额与商品价格不一致");
        verifyNoInteractions(orderMapper, itemMapper, redis);
    }

    @Test
    void rejectsOffSaleProductBeforeReservingStock() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        when(productMapper.selectById(10001L)).thenReturn(product("OFF_SALE", "199.00"));
        OrderService service = new OrderService(redis, rabbit, mock(TradeOrderMapper.class),
                mock(TradeOrderItemMapper.class), productMapper);

        // 阶段 A：下单前校验商品在售状态，避免下架商品继续扣减库存。
        assertThatThrownBy(() -> service.create(new OrderController.CreateOrderRequest(
                "user-1", 10001L, 1, new BigDecimal("199.00"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("商品不存在或已下架");
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsPaymentFromAnotherUserBeforeTakingIdempotencyToken() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrder order = new TradeOrder();
        order.setOrderNo("XH12345678");
        order.setUserId("user-1");
        order.setStatus("PENDING_PAYMENT");
        when(orderMapper.selectById("XH12345678")).thenReturn(order);
        OrderService service = new OrderService(redis, rabbit, orderMapper,
                mock(TradeOrderItemMapper.class), mock(ProductMapper.class));

        // 阶段 A：支付前校验订单归属，越权请求不能占用幂等 Token。
        assertThatThrownBy(() -> service.pay("XH12345678", "user-2", "token-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("无权支付该订单");
        verifyNoInteractions(redis);
    }

    @Test
    void doesNotReportPaymentSuccessWhenTimeoutCancellationWinsRace() {
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("pay:idempotent:token-1"), eq("XH12345678"), any())).thenReturn(true);
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        TradeOrder pending = new TradeOrder();
        pending.setOrderNo("XH12345678");
        pending.setUserId("user-1");
        pending.setStatus("PENDING_PAYMENT");
        TradeOrder cancelled = new TradeOrder();
        cancelled.setOrderNo("XH12345678");
        cancelled.setUserId("user-1");
        cancelled.setStatus("CANCELLED");
        when(orderMapper.selectById("XH12345678")).thenReturn(pending, cancelled);
        when(orderMapper.update(any(TradeOrder.class), any())).thenReturn(0);
        OrderService service = new OrderService(redis, mock(RabbitTemplate.class), orderMapper,
                mock(TradeOrderItemMapper.class), mock(ProductMapper.class));

        // 阶段 A：订单超时取消先完成时，支付条件更新失败并明确返回冲突，不得误报支付成功。
        assertThatThrownBy(() -> service.pay("XH12345678", "user-1", "token-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("订单已取消，支付失败");
        verify(redis).delete("pay:idempotent:token-1");
    }

    /** 确认店铺订单列表批量读取物流，并按订单顺序组装结果。 */
    @Test
    void loadsStoreOrderLogisticsWithOneBatchQuery() {
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        LogisticsMapper logisticsMapper = mock(LogisticsMapper.class);
        TradeOrder first = storeOrder("ORDER-1");
        TradeOrder second = storeOrder("ORDER-2");
        LogisticsRecord secondLogistics = logistics("ORDER-2");
        LogisticsRecord firstLogistics = logistics("ORDER-1");
        when(orderMapper.selectStoreOrders(5L)).thenReturn(List.of(first, second));
        when(logisticsMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(secondLogistics, firstLogistics));

        OrderService service = new OrderService(mock(RedisTemplate.class), mock(RabbitTemplate.class),
                orderMapper, mock(TradeOrderItemMapper.class), mock(ProductMapper.class),
                mock(CartItemMapper.class), mock(ProductInventoryMapper.class), logisticsMapper);

        List<OrderService.StoreOrderView> result = service.storeOrders(5L);

        assertThat(result).extracting(view -> view.order().getOrderNo())
                .containsExactly("ORDER-1", "ORDER-2");
        assertThat(result).extracting(view -> view.logistics().getOrderNo())
                .containsExactly("ORDER-1", "ORDER-2");
        verify(logisticsMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
        verify(logisticsMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    /** 确认没有店铺订单时不发起无意义的物流查询。 */
    @Test
    void doesNotQueryLogisticsWhenStoreHasNoOrders() {
        TradeOrderMapper orderMapper = mock(TradeOrderMapper.class);
        LogisticsMapper logisticsMapper = mock(LogisticsMapper.class);
        when(orderMapper.selectStoreOrders(5L)).thenReturn(new ArrayList<>());

        OrderService service = new OrderService(mock(RedisTemplate.class), mock(RabbitTemplate.class),
                orderMapper, mock(TradeOrderItemMapper.class), mock(ProductMapper.class),
                mock(CartItemMapper.class), mock(ProductInventoryMapper.class), logisticsMapper);

        assertThat(service.storeOrders(5L)).isEmpty();
        verifyNoInteractions(logisticsMapper);
    }

    private TradeOrder storeOrder(String orderNo) {
        TradeOrder order = new TradeOrder();
        order.setOrderNo(orderNo);
        order.setStoreId(5L);
        order.setParentOrderNo("PARENT-1");
        return order;
    }

    private LogisticsRecord logistics(String orderNo) {
        LogisticsRecord record = new LogisticsRecord();
        record.setOrderNo(orderNo);
        record.setStatus("SHIPPED");
        return record;
    }

    private Product product(String status, String price) {
        return product(10001L, 1L, status, price);
    }

    /** 构造结算测试用的店铺商品快照。 */
    private Product product(Long skuId, Long storeId, String price) {
        return product(skuId, storeId, "ON_SALE", price);
    }

    /** 构造指定状态、店铺和价格的商品数据。 */
    private Product product(Long skuId, Long storeId, String status, String price) {
        Product product = new Product();
        product.setId(skuId);
        product.setStoreId(storeId);
        product.setPrice(new BigDecimal(price));
        product.setStatus(status);
        product.setName("测试商品");
        return product;
    }

    /** 构造结算测试中的购物车行。 */
    private CartItem cartItem(Long skuId, int quantity) {
        CartItem item = new CartItem();
        item.setUserId("user-1");
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }
}
