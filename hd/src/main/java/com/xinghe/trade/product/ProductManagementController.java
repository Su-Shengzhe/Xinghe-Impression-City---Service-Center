package com.xinghe.trade.product;

import com.xinghe.trade.common.ApiResponse;
import com.xinghe.trade.inventory.ProductInventory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 提供店铺商品维护和数据库库存管理接口。 */
@RestController
@RequestMapping("/api/store/products")
public class ProductManagementController {
    private final ProductManagementService service;

    /** 注入商品管理服务，由服务层执行店铺归属校验。 */
    public ProductManagementController(ProductManagementService service) { this.service = service; }

    /** 查询当前店铺商品及库存；店铺归属由服务层校验。 */
    @GetMapping
    public ApiResponse<List<ProductManagementService.ProductStockView>> list(
            @RequestHeader("X-Store-Id") @Positive Long storeId) {
        return ApiResponse.ok(service.list(storeId));
    }

    /** 为请求指定的店铺创建商品和初始库存。 */
    @PostMapping
    public ApiResponse<Product> create(@RequestHeader("X-Store-Id") @Positive Long storeId,
                                       @Valid @RequestBody ProductRequest request) {
        return ApiResponse.ok(service.create(storeId, request.toServiceRequest()));
    }

    /** 更新当前店铺商品资料，库存需通过专用接口管理。 */
    @PutMapping("/{skuId}")
    public ApiResponse<Product> update(@RequestHeader("X-Store-Id") @Positive Long storeId,
                                       @PathVariable @Positive Long skuId,
                                       @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(service.update(storeId, skuId,
                new ProductManagementService.ProductUpdateRequest(request.name(), request.description(), request.price())));
    }

    /** 切換店鋪商品的在售或下架狀態。 */
    @PutMapping("/{skuId}/status")
    public ApiResponse<Product> setStatus(@RequestHeader("X-Store-Id") @Positive Long storeId,
                                          @PathVariable @Positive Long skuId,
                                          @Valid @RequestBody ProductStatusRequest request) {
        return ApiResponse.ok(service.setStatus(storeId, skuId, request.status()));
    }

    /** 設定可用庫存，不改動已由待支付訂單鎖定的数量。 */
    @PutMapping("/{skuId}/inventory")
    public ApiResponse<ProductInventory> setInventory(@RequestHeader("X-Store-Id") @Positive Long storeId,
                                                       @PathVariable @Positive Long skuId,
                                                       @Valid @RequestBody InventoryRequest request) {
        return ApiResponse.ok(service.setInventory(storeId, skuId, request.availableQuantity()));
    }

    /** 商品创建参数同时包含库存；更新接口只接收商品资料，避免误改库存。 */
    public record ProductRequest(@NotNull @Positive Long skuId, @NotBlank String name, String description,
                                 @NotNull @jakarta.validation.constraints.DecimalMin("0.00") java.math.BigDecimal price,
                                 @NotNull @Min(0) Integer availableQuantity) {
        private ProductManagementService.ProductRequest toServiceRequest() {
            return new ProductManagementService.ProductRequest(skuId, name, description, price, availableQuantity);
        }
    }

    /** 商品资料编辑参数。 */
    public record ProductUpdateRequest(@NotBlank String name, String description,
                                       @NotNull @jakarta.validation.constraints.DecimalMin("0.00") java.math.BigDecimal price) {}

    /** 商品上下架状态参数。 */
    public record ProductStatusRequest(@NotBlank String status) {}

    /** 可用库存调整参数。 */
    public record InventoryRequest(@NotNull @Min(0) Integer availableQuantity) {}
}