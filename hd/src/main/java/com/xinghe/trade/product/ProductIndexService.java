package com.xinghe.trade.product;

import com.xinghe.trade.messaging.MessageProcessingService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@ConditionalOnProperty(name = "xinghe.search.elasticsearch.enabled", havingValue = "true")
public class ProductIndexService {
    private final ProductMapper productMapper;
    private final ProductDocumentRepository repository;
    private final ElasticsearchOperations operations;
    private final MessageProcessingService processing;

    public ProductIndexService(ProductMapper productMapper, ProductDocumentRepository repository,
                               ElasticsearchOperations operations, MessageProcessingService processing) {
        this.productMapper = productMapper;
        this.repository = repository;
        this.operations = operations;
        this.processing = processing;
    }

    @Transactional
    public void synchronize(Long skuId) {
        Product current = productMapper.selectById(skuId);
        if (current == null) {
            repository.deleteById(String.valueOf(skuId));
        } else {
            saveIfNewer(current);
        }
    }

    public void rebuild() {
        var indexOperations = operations.indexOps(ProductDocument.class);
        if (indexOperations.exists()) indexOperations.delete();
        indexOperations.createWithMapping();
        List<Product> products = productMapper.selectList(null);
        for (Product product : products) saveIfNewer(product);
        indexOperations.refresh();
    }

    private void saveIfNewer(Product product) {
        String id = String.valueOf(product.getId());
        ProductDocument candidate = new ProductDocument(product);
        ProductDocument indexed = repository.findById(id).orElse(null);
        if (indexed != null && indexed.getUpdatedAt() != null && candidate.getUpdatedAt() != null
                && indexed.getUpdatedAt().isAfter(candidate.getUpdatedAt())) return;
        repository.save(candidate);
    }

}