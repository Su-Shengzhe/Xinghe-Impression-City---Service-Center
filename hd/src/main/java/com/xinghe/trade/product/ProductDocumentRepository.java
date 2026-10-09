package com.xinghe.trade.product;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductDocumentRepository extends ElasticsearchRepository<ProductDocument, String> {
    List<ProductDocument> findTop20ByStatusAndNameContainingOrStatusAndDescriptionContainingOrderByUpdatedAtDesc(
            String status1, String name, String status2, String description);
    List<ProductDocument> findTop20ByStatusOrderByUpdatedAtDesc(String status);
}