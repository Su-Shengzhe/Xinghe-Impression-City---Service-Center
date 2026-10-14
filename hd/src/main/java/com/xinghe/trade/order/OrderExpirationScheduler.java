package com.xinghe.trade.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Database fallback when RabbitMQ is disabled, delayed, or an event is lost. */
@Component
public class OrderExpirationScheduler {
    private static final int BATCH_SIZE = 100;
    private final TradeOrderMapper orders;
    private final OrderTimeoutConsumer cancellation;
    private LocalDateTime afterExpiry;
    private String afterOrderNo;

    public OrderExpirationScheduler(TradeOrderMapper orders, OrderTimeoutConsumer cancellation) {
        this.orders = orders;
        this.cancellation = cancellation;
    }

    @Scheduled(fixedDelayString = "${xinghe.order.expiry-scan-ms:30000}")
    public void cancelExpired() {
        List<TradeOrder> batch = orders.selectExpiredOrders(afterExpiry, afterOrderNo, BATCH_SIZE);
        for (TradeOrder order : batch) {
            // Each order is its own transaction; one malformed order cannot suppress the entire batch.
            try {
                cancellation.cancel(order.getOrderNo());
            } catch (RuntimeException exception) {
                // The next scan retries this still-pending order; surface failures to operations.
                org.slf4j.LoggerFactory.getLogger(OrderExpirationScheduler.class)
                        .error("Failed to expire order {}", order.getOrderNo(), exception);
            }
        }
        if (batch.size() == BATCH_SIZE) {
            TradeOrder last = batch.get(batch.size() - 1);
            afterExpiry = last.getExpiresAt();
            afterOrderNo = last.getOrderNo();
        } else {
            // Wrap around so failed rows and newly expired earlier orders are revisited.
            afterExpiry = null;
            afterOrderNo = null;
        }
    }
}
