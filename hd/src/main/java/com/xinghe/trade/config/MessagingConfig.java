package com.xinghe.trade.config;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@ConditionalOnProperty(name = "xinghe.messaging.enabled", havingValue = "true")
public class MessagingConfig {
    public static final String ORDER_EXCHANGE = "xinghe.order.exchange";
    public static final String ORDER_TIMEOUT_QUEUE = "xinghe.order.timeout";
    public static final String ORDER_DLX = "xinghe.order.dlx";
    public static final String EVENT_EXCHANGE = "xinghe.events.exchange";
    public static final String RETRY_EXCHANGE = "xinghe.events.retry.exchange";
    public static final String EVENT_DLX = "xinghe.events.dlx";
    // 订单超时消息先进入延迟队列，消息过期后由死信交换机转发取消事件。
    @Bean DirectExchange orderExchange() { return new DirectExchange(ORDER_EXCHANGE); }
    @Bean DirectExchange eventExchange() { return new DirectExchange(EVENT_EXCHANGE); }
    @Bean DirectExchange retryExchange() { return new DirectExchange(RETRY_EXCHANGE); }
    @Bean TopicExchange eventDeadLetterExchange() { return new TopicExchange(EVENT_DLX); }
    // 死信交换机负责将过期订单路由到取消队列。
    @Bean DirectExchange deadLetterExchange() { return new DirectExchange(ORDER_DLX); }
    // 持久化超时队列并配置过期消息的死信路由。
    @Bean Queue orderTimeoutQueue() { return QueueBuilder.durable(ORDER_TIMEOUT_QUEUE).deadLetterExchange(ORDER_DLX).deadLetterRoutingKey("order.cancel").build(); }
    // 取消队列由 OrderTimeoutConsumer 消费并执行状态变更与库存补偿。
    @Bean Queue orderCancelQueue() { return QueueBuilder.durable("xinghe.order.cancel").deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("order.cancel.retry.1").build(); }
    @Bean Queue orderRetryQueue() { return QueueBuilder.durable("xinghe.order.cancel.retry.1").ttl(5000).deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("order.cancel.attempt.2").build(); }
    @Bean Queue orderSecondAttemptQueue() { return QueueBuilder.durable("xinghe.order.cancel.attempt.2").deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("order.cancel.retry.2").build(); }
    @Bean Queue orderRetryFinalQueue() { return QueueBuilder.durable("xinghe.order.cancel.retry.2").ttl(30000).deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("order.cancel.attempt.3").build(); }
    @Bean Queue orderThirdAttemptQueue() { return QueueBuilder.durable("xinghe.order.cancel.attempt.3").deadLetterExchange(EVENT_DLX).deadLetterRoutingKey("order.cancel").build(); }
    @Bean Queue productIndexQueue() { return QueueBuilder.durable("xinghe.product.index").deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("product.index.retry.1").build(); }
    @Bean Queue productIndexRetryQueue() { return QueueBuilder.durable("xinghe.product.index.retry.1").ttl(5000).deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("product.index.attempt.2").build(); }
    @Bean Queue productSecondAttemptQueue() { return QueueBuilder.durable("xinghe.product.index.attempt.2").deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("product.index.retry.2").build(); }
    @Bean Queue productIndexRetryFinalQueue() { return QueueBuilder.durable("xinghe.product.index.retry.2").ttl(30000).deadLetterExchange(RETRY_EXCHANGE).deadLetterRoutingKey("product.index.attempt.3").build(); }
    @Bean Queue productThirdAttemptQueue() { return QueueBuilder.durable("xinghe.product.index.attempt.3").deadLetterExchange(EVENT_DLX).deadLetterRoutingKey("product.index").build(); }
    @Bean Queue businessDeadLetterQueue() { return QueueBuilder.durable("xinghe.business.dead").build(); }
    // 将订单超时路由键绑定到延迟队列。
    @Bean Binding timeoutBinding() { return BindingBuilder.bind(orderTimeoutQueue()).to(orderExchange()).with("order.timeout"); }
    @Bean Binding eventTimeoutBinding() { return BindingBuilder.bind(orderTimeoutQueue()).to(eventExchange()).with("order.timeout"); }
    // 将死信路由键绑定到订单取消队列。
    @Bean Binding cancelBinding() { return BindingBuilder.bind(orderCancelQueue()).to(deadLetterExchange()).with("order.cancel"); }
    @Bean Binding orderRetryBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(orderRetryQueue()).to(retryExchange).with("order.cancel.retry.1"); }
    @Bean Binding orderSecondAttemptBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(orderSecondAttemptQueue()).to(retryExchange).with("order.cancel.attempt.2"); }
    @Bean Binding orderRetryFinalBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(orderRetryFinalQueue()).to(retryExchange).with("order.cancel.retry.2"); }
    @Bean Binding orderThirdAttemptBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(orderThirdAttemptQueue()).to(retryExchange).with("order.cancel.attempt.3"); }
    @Bean Binding productBinding(@Qualifier("eventExchange") DirectExchange eventExchange) { return BindingBuilder.bind(productIndexQueue()).to(eventExchange).with("product.index"); }
    @Bean Binding productRetryBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(productIndexRetryQueue()).to(retryExchange).with("product.index.retry.1"); }
    @Bean Binding productSecondAttemptBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(productSecondAttemptQueue()).to(retryExchange).with("product.index.attempt.2"); }
    @Bean Binding productRetryFinalBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(productIndexRetryFinalQueue()).to(retryExchange).with("product.index.retry.2"); }
    @Bean Binding productThirdAttemptBinding(@Qualifier("retryExchange") DirectExchange retryExchange) { return BindingBuilder.bind(productThirdAttemptQueue()).to(retryExchange).with("product.index.attempt.3"); }
    @Bean Binding businessDeadBinding() { return BindingBuilder.bind(businessDeadLetterQueue()).to(eventDeadLetterExchange()).with("#"); }
}
