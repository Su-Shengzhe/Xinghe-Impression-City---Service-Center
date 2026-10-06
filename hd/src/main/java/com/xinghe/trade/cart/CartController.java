package com.xinghe.trade.cart;

import com.xinghe.trade.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ApiResponse<List<CartService.CartLine>> list(@RequestHeader("X-User-Id") String userId) {
        return ApiResponse.ok(cartService.list(userId));
    }

    @PostMapping("/items")
    public ApiResponse<CartService.CartLine> add(@RequestHeader("X-User-Id") String userId,
                                                  @Valid @RequestBody CartQuantityRequest request) {
        return ApiResponse.ok(cartService.add(userId, request.skuId(), request.quantity()));
    }

    @PutMapping("/items/{skuId}")
    public ApiResponse<CartService.CartLine> setQuantity(@RequestHeader("X-User-Id") String userId,
                                                          @PathVariable @Positive Long skuId,
                                                          @Valid @RequestBody QuantityRequest request) {
        return ApiResponse.ok(cartService.setQuantity(userId, skuId, request.quantity()));
    }

    @DeleteMapping("/items/{skuId}")
    public ApiResponse<Void> remove(@RequestHeader("X-User-Id") String userId,
                                    @PathVariable @Positive Long skuId) {
        cartService.remove(userId, skuId);
        return ApiResponse.ok(null);
    }

    public record CartQuantityRequest(@NotNull @Positive Long skuId,
                                     @NotNull @Min(1) @Max(99) Integer quantity) {}

    public record QuantityRequest(@NotNull @Min(1) @Max(99) Integer quantity) {}
}