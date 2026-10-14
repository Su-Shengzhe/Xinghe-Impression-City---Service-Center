package com.xinghe.trade.product;

import com.xinghe.trade.messaging.MessageProcessingService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(name = {"xinghe.messaging.enabled", "xinghe.search.elasticsearch.enabled"}, havingValue = "true")
public class ProductIndexConsumer {
    private final ProductIndexService indexService;
    private final MessageProcessingService processing;

    public ProductIndexConsumer(ProductIndexService indexService, MessageProcessingService processing) {
        this.indexService = indexService;
        this.processing = processing;
    }

    @RabbitListener(queues = {"xinghe.product.index", "xinghe.product.index.attempt.2",
            "xinghe.product.index.attempt.3"})
    public void consume(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        Long skuId = extractSkuId(payload);
        processing.processOnce(message.getMessageProperties().getMessageId(), "PRODUCT_INDEX",
                () -> indexService.synchronize(skuId));
    }

    private Long extractSkuId(String json) {
        Matcher matcher = Pattern.compile("\\\"skuId\\\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("商品索引事件缺少 skuId");
        return Long.valueOf(matcher.group(1));
    }
}
