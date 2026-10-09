package com.xinghe.trade.product;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.ValueConverter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Document(indexName = "xinghe-products")
public class ProductDocument {
    @Id private String id;
    @Field(type = FieldType.Long) private Long skuId;
    @Field(type = FieldType.Long) private Long storeId;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart") private String name;
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart") private String description;
    @Field(type = FieldType.Scaled_Float, scalingFactor = 100) private BigDecimal price;
    @Field(type = FieldType.Keyword) private String status;
    @Field(type = FieldType.Date)
    @ValueConverter(ProductDateTimeConverter.class)
    private LocalDateTime updatedAt;

    public ProductDocument() {}
    public ProductDocument(Product product) {
        id = String.valueOf(product.getId());
        skuId = product.getId();
        storeId = product.getStoreId();
        name = product.getName();
        description = product.getDescription();
        price = product.getPrice();
        status = product.getStatus();
        updatedAt = product.getUpdatedAt();
    }
    public String getId() { return id; }
    public Long getSkuId() { return skuId; }
    public Long getStoreId() { return storeId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getPrice() { return price; }
    public String getStatus() { return status; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}