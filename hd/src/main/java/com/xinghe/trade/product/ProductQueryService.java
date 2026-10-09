package com.xinghe.trade.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

@Service
public class ProductQueryService {
    private final ProductMapper mapper;
    private final ProductDocumentRepository repository;
    @org.springframework.beans.factory.annotation.Value("${xinghe.search.elasticsearch.enabled:false}")
    private boolean elasticsearchEnabled;

    public ProductQueryService(ProductMapper mapper,
                               @Qualifier("productDocumentRepository") java.util.Optional<ProductDocumentRepository> repository) {
        this.mapper = mapper;
        this.repository = repository.orElse(null);
    }

    public List<Product> search(String keyword) {
        // 仅查询在售商品，按更新时间排序并限制最多 20 条，满足客服快速检索场景。
        // 阶段 A：商品搜索入口限制关键词长度，避免超长输入拖慢数据库查询。
        if (keyword != null && keyword.length() > 80) throw new IllegalArgumentException("商品关键词不能超过 80 个字符");
        if (elasticsearchEnabled && repository != null) {
            try {
                List<ProductDocument> documents = StringUtils.hasText(keyword)
                        ? repository.findTop20ByStatusAndNameContainingOrStatusAndDescriptionContainingOrderByUpdatedAtDesc(
                        "ON_SALE", keyword, "ON_SALE", keyword)
                        : repository.findTop20ByStatusOrderByUpdatedAtDesc("ON_SALE");
                return documents.stream().map(this::toProduct).toList();
            } catch (RuntimeException ignored) {
                // ES is a read projection; MySQL remains the authoritative fallback.
            }
        }
        LambdaQueryWrapper<Product> query = new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, "ON_SALE")
                .orderByDesc(Product::getUpdatedAt);
        if (StringUtils.hasText(keyword)) query.and(wrapper -> wrapper.like(Product::getName, keyword).or().like(Product::getDescription, keyword));
        return mapper.selectList(query.last("LIMIT 20"));
    }

    private Product toProduct(ProductDocument document) {
        Product product = new Product();
        product.setId(document.getSkuId());
        product.setStoreId(document.getStoreId());
        product.setName(document.getName());
        product.setDescription(document.getDescription());
        product.setPrice(document.getPrice());
        product.setStatus(document.getStatus());
        product.setUpdatedAt(document.getUpdatedAt());
        return product;
    }
}
