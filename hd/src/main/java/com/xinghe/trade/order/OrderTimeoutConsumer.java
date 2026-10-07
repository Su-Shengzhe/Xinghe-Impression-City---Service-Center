package com.xinghe.trade.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xinghe.trade.inventory.ProductInventoryMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 消费订单延迟消息，取消未支付订单并释放数据库中的锁定库存。 */
@Component
@ConditionalOnProperty(name = "xinghe.messaging.enabled", havingValue = "true")
public class OrderTimeoutConsumer {
    private final TradeOrderMapper orderMapper;
    private final TradeOrderItemMapper orderItemMapper;
    private final ProductInventoryMapper inventoryMapper;

    @Autowired
    public OrderTimeoutConsumer(TradeOrderMapper orderMapper,
                                TradeOrderItemMapper orderItemMapper,
                                ProductInventoryMapper inventoryMapper) {
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.inventoryMapper = inventoryMapper;
    }

    /** Compatibility constructor; timeout compensation no longer uses Redis inventory. */
    public OrderTimeoutConsumer(RedisTemplate<String, Object> ignoredRedis,
                                TradeOrderMapper orderMapper,
                                ProductInventoryMapper inventoryMapper) {
        this(orderMapper, null, inventoryMapper);
    }

    /** Compatibility constructor for callers that only provide order access. */
    public OrderTimeoutConsumer(RedisTemplate<String, Object> ignoredRedis,
                                TradeOrderMapper orderMapper) {
        this(orderMapper, null, null);
    }

    @RabbitListener(queues = "xinghe.order.cancel")
    @Transactional
    public void cancel(String orderNo) {
        TradeOrder order = orderMapper.selectById(orderNo);
        if (order == null) return;
        if (order.getParentOrderNo() == null && order.getSkuId() == null) {
            List<TradeOrder> children = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                    .eq(TradeOrder::getParentOrderNo, orderNo));
            for (TradeOrder child : children) cancelChild(child);
            refreshParent(orderNo);
            return;
        }
        cancelChild(order);
    }

    /** 只有条件取消成功的消息才释放库存，重复投递不会重复补偿。 */
    private void cancelChild(TradeOrder order) {
        TradeOrder update = new TradeOrder();
        update.setOrderNo(order.getOrderNo());
        update.setStatus("CANCELLED");
        update.setUpdatedAt(LocalDateTime.now());
        int updated = orderMapper.update(update, new LambdaUpdateWrapper<TradeOrder>()
                .eq(TradeOrder::getOrderNo, order.getOrderNo())
                .eq(TradeOrder::getStatus, "PENDING_PAYMENT"));
        if (updated != 1) return;

        List<TradeOrderItem> items = orderItemMapper == null ? List.of() : orderItemMapper.selectList(
                new LambdaQueryWrapper<TradeOrderItem>().eq(TradeOrderItem::getOrderNo, order.getOrderNo()));
        if (items.isEmpty() && order.getSkuId() != null && order.getQuantity() != null) {
            release(order.getSkuId(), order.getQuantity());
        }
        for (TradeOrderItem item : items) release(item.getSkuId(), item.getQuantity());
        if (order.getParentOrderNo() != null) refreshParent(order.getParentOrderNo());
    }

    private void release(Long skuId, Integer quantity) {
        if (inventoryMapper == null || inventoryMapper.releaseLocked(skuId, quantity) != 1) {
            throw new IllegalStateException("锁定库存状态异常");
        }
    }

    /** 所有子订单终态后按统一规则刷新父订单，避免超时路径覆盖发货状态。 */
    private void refreshParent(String parentOrderNo) {
        List<TradeOrder> children = orderMapper.selectList(new LambdaQueryWrapper<TradeOrder>()
                .eq(TradeOrder::getParentOrderNo, parentOrderNo));
        if (children.isEmpty() || children.stream().anyMatch(child -> "PENDING_PAYMENT".equals(child.getStatus()))) return;
        TradeOrder parent = new TradeOrder();
        parent.setOrderNo(parentOrderNo);
        parent.setStatus(OrderStatus.fromChildren(children));
        parent.setUpdatedAt(LocalDateTime.now());
        orderMapper.updateById(parent);
    }

}