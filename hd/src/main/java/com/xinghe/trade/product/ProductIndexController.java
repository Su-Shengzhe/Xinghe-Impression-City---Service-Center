package com.xinghe.trade.product;

import com.xinghe.trade.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/store/products")
@ConditionalOnProperty(name = "xinghe.search.elasticsearch.enabled", havingValue = "true")
public class ProductIndexController {
    private final ProductIndexService indexService;

    public ProductIndexController(ProductIndexService indexService) { this.indexService = indexService; }

    @PostMapping("/rebuild-index")
    public ApiResponse<String> rebuild() {
        indexService.rebuild();
        return ApiResponse.ok("商品索引重建完成");
    }
}