package com.xinghe.trade.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import com.xinghe.trade.order.OrderTimeoutConsumer;
import com.xinghe.trade.product.ProductIndexConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingConfigTest {
    private final MessagingConfig config = new MessagingConfig();

    @Test
    void delayedQueuesReturnToConsumableAttemptsBeforeDeadLettering() throws Exception {
        assertDeadLetter(config.orderRetryQueue(), MessagingConfig.RETRY_EXCHANGE, "order.cancel.attempt.2");
        assertDeadLetter(config.orderSecondAttemptQueue(), MessagingConfig.RETRY_EXCHANGE, "order.cancel.retry.2");
        assertDeadLetter(config.orderRetryFinalQueue(), MessagingConfig.RETRY_EXCHANGE, "order.cancel.attempt.3");
        assertDeadLetter(config.orderThirdAttemptQueue(), MessagingConfig.EVENT_DLX, "order.cancel");
        assertDeadLetter(config.orderCancelQueue(), MessagingConfig.RETRY_EXCHANGE, "order.cancel.retry.1");
        assertThat(config.orderRetryQueue().getArguments()).containsEntry("x-message-ttl", 5000);
        assertThat(config.orderRetryFinalQueue().getArguments()).containsEntry("x-message-ttl", 30000);

        assertDeadLetter(config.productIndexRetryQueue(), MessagingConfig.RETRY_EXCHANGE, "product.index.attempt.2");
        assertDeadLetter(config.productSecondAttemptQueue(), MessagingConfig.RETRY_EXCHANGE, "product.index.retry.2");
        assertDeadLetter(config.productIndexRetryFinalQueue(), MessagingConfig.RETRY_EXCHANGE, "product.index.attempt.3");
        assertDeadLetter(config.productThirdAttemptQueue(), MessagingConfig.EVENT_DLX, "product.index");
        assertDeadLetter(config.productIndexQueue(), MessagingConfig.RETRY_EXCHANGE, "product.index.retry.1");

        RabbitListener orderListener = OrderTimeoutConsumer.class.getMethod("consume", org.springframework.amqp.core.Message.class)
                .getAnnotation(RabbitListener.class);
        RabbitListener productListener = ProductIndexConsumer.class.getMethod("consume", org.springframework.amqp.core.Message.class)
                .getAnnotation(RabbitListener.class);
        assertThat(orderListener.queues()).contains("xinghe.order.cancel.attempt.2", "xinghe.order.cancel.attempt.3");
        assertThat(orderListener.autoStartup()).isEqualTo("${xinghe.messaging.enabled:false}");
        assertThat(productListener.queues()).contains("xinghe.product.index.attempt.2", "xinghe.product.index.attempt.3");
    }

    private void assertDeadLetter(Queue queue, String exchange, String routingKey) {
        assertThat(queue.getArguments()).containsEntry("x-dead-letter-exchange", exchange)
                .containsEntry("x-dead-letter-routing-key", routingKey);
    }
}
