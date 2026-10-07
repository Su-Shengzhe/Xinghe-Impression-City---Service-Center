package com.xinghe.trade.cart;

import com.xinghe.trade.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 处理当前用户购物车的查询与商品项变更请求。 */
@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService cartService;

    /** 创建购物车控制器并注入业务服务。 */
    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /** 查询请求头所标识用户的购物车。 */
    @GetMapping
    public ApiResponse<List<CartService.CartLine>> list(@RequestHeader("X-User-Id") String userId) {
        return ApiResponse.ok(cartService.list(userId));
    }

    /** 添加商品；重复添加同一 SKU 时由服务端累加数量。 */
    @PostMapping("/items")
    public ApiResponse<CartService.CartLine> add(@RequestHeader("X-User-Id") String userId,
                                                  @Valid @RequestBody CartQuantityRequest request) {
        return ApiResponse.ok(cartService.add(userId, request.skuId(), request.quantity()));
    }

    /** 将当前用户购物车中指定 SKU 的数量设置为请求值。 */
    @PutMapping("/items/{skuId}")
    public ApiResponse<CartService.CartLine> setQuantity(@RequestHeader("X-User-Id") String userId,
                                                          @PathVariable @Positive Long skuId,
                                                          @Valid @RequestBody QuantityRequest request) {
        return ApiResponse.ok(cartService.setQuantity(userId, skuId, request.quantity()));
    }

    /** 删除当前用户购物车中指定 SKU。 */
    @DeleteMapping("/items/{skuId}")
    public ApiResponse<Void> remove(@RequestHeader("X-User-Id") String userId,
                                    @PathVariable @Positive Long skuId) {
        cartService.remove(userId, skuId);
        return ApiResponse.ok(null);
    }

    /** 添加购物车商品的请求参数，不包含用户身份或商品价格。 */
    public record CartQuantityRequest(@NotNull @Positive Long skuId,
                                     @NotNull @Min(1) @Max(99) Integer quantity) {}

    /** 修改购物车商品数量的请求参数。 */
    public record QuantityRequest(@NotNull @Min(1) @Max(99) Integer quantity) {}
}