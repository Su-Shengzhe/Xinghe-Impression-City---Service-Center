package com.xinghe.trade.order;

import com.xinghe.trade.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import com.xinghe.trade.logistics.LogisticsRecord;

/** 提供订单创建、购物车结算和支付确认接口。 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }
    /** 创建单笔订单；请求头用户必须与请求体用户一致。 */
    @PostMapping
    public ApiResponse<OrderService.OrderResult> create(@RequestHeader("X-User-Id") String userId,
                                                        @Valid @RequestBody CreateOrderRequest request) {
        // 创建订单前校验请求头用户与请求体用户一致，避免冒用其他用户下单。
        String normalizedUserId = requireSameUser(userId, request.userId());
        CreateOrderRequest normalizedRequest = new CreateOrderRequest(
                normalizedUserId, request.skuId(), request.quantity(), request.amount());
        return ApiResponse.ok(service.create(normalizedRequest));
    }

    /** 将当前用户购物车商品结算为待支付订单。 */
    @PostMapping("/checkout")
    public ApiResponse<List<OrderService.OrderResult>> checkout(@RequestHeader("X-User-Id") String userId,
                                                                @RequestHeader("Idempotency-Token") String token) {
        return ApiResponse.ok(service.checkout(requireUser(userId), token));
    }
    /** 确认支付；通过幂等 Token 防止重复请求造成重复支付。 */
    @PostMapping("/{orderNo}/pay")
    public ApiResponse<OrderService.OrderResult> pay(@PathVariable String orderNo,
                                                     @RequestHeader("X-User-Id") String userId,
                                                     @RequestHeader("Idempotency-Token") String token) {
        // 使用幂等 Token 发起支付，重复请求由服务层返回安全结果。
        return ApiResponse.ok(service.pay(orderNo, requireUser(userId), token));
    }

    /** 查询订单详情；服务层负责校验订单属于当前请求用户。 */
    @GetMapping("/{orderNo}")
    public ApiResponse<OrderService.OrderView> detail(@PathVariable String orderNo,
                                                       @RequestHeader("X-User-Id") String userId) {
        return ApiResponse.ok(service.detail(requireUser(userId), orderNo));
    }

    /** 取消用户自己的待支付订单，并同步释放所有店铺子订单库存。 */
    @PostMapping("/{orderNo}/cancel")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @RequestHeader("X-User-Id") String userId) {
        service.cancel(requireUser(userId), orderNo);
        return ApiResponse.ok(null);
    }

    /** 店铺提交物流信息并发货，X-Store-Id 由服务层与子订单所属店铺核验。 */
    @PostMapping("/{orderNo}/ship")
    public ApiResponse<LogisticsRecord> ship(@PathVariable String orderNo,
                                             @RequestHeader("X-Store-Id") @Positive Long storeId,
                                             @Valid @RequestBody ShipRequest request) {
        return ApiResponse.ok(service.ship(storeId, orderNo, request.company(), request.trackingNo()));
    }

    /** 店铺订单工作台只读取当前店铺的子订单。 */
    @GetMapping("/store")
    public ApiResponse<List<OrderService.StoreOrderView>> storeOrders(
            @RequestHeader("X-Store-Id") @Positive Long storeId) {
        return ApiResponse.ok(service.storeOrders(storeId));
    }

    // 阶段 A：开发联调期间校验请求头与请求体用户一致，避免订单接口被冒用。
    private String requireSameUser(String headerUserId, String bodyUserId) {
        String normalizedHeaderUserId = requireUser(headerUserId);
        String normalizedBodyUserId = bodyUserId == null ? "" : bodyUserId.trim();
        if (!normalizedHeaderUserId.equals(normalizedBodyUserId)) {
            throw new SecurityException("请求用户与订单用户不一致");
        }
        return normalizedHeaderUserId;
    }

    private String requireUser(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("X-User-Id 请求头不能为空");
        return userId.trim();
    }
    public record CreateOrderRequest(@NotBlank String userId, @NotNull Long skuId, @NotNull Integer quantity, @NotNull BigDecimal amount) {}

    /** 店铺发货所需承运商和运单编号。 */
    public record ShipRequest(@NotBlank String company, @NotBlank String trackingNo) {}
}
