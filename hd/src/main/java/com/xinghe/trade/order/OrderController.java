package com.xinghe.trade.order;

import com.xinghe.trade.common.ApiResponse;
import com.xinghe.trade.auth.AuthenticatedUser;
import com.xinghe.trade.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.util.List;
import com.xinghe.trade.logistics.LogisticsRecord;

/** 提供订单创建、购物车结算和支付确认接口。 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }
    /** 创建单笔订单；用户归属始终来自登录会话。 */
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<OrderService.OrderResult> create(@CurrentUser AuthenticatedUser user,
                                                        @Valid @RequestBody CreateOrderRequest request) {
        CreateOrderRequest normalizedRequest = new CreateOrderRequest(
                user.userId(), request.skuId(), request.quantity(), request.amount());
        return ApiResponse.ok(service.create(normalizedRequest));
    }

    /** 将当前用户购物车商品结算为待支付订单。 */
    @PostMapping("/checkout")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<List<OrderService.OrderResult>> checkout(@CurrentUser AuthenticatedUser user,
                                                                @RequestHeader("Idempotency-Token") String token) {
        return ApiResponse.ok(service.checkout(user.userId(), token));
    }
    /** 确认支付；通过幂等 Token 防止重复请求造成重复支付。 */
    @PostMapping("/{orderNo}/pay")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<OrderService.OrderResult> pay(@PathVariable String orderNo,
                                                      @CurrentUser AuthenticatedUser user,
                                                     @RequestHeader("Idempotency-Token") String token) {
        // 使用幂等 Token 发起支付，重复请求由服务层返回安全结果。
        return ApiResponse.ok(service.pay(orderNo, user.userId(), token));
    }

    /** 查询订单详情；服务层负责校验订单属于当前请求用户。 */
    @GetMapping("/{orderNo}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<OrderService.OrderView> detail(@PathVariable String orderNo,
                                                       @CurrentUser AuthenticatedUser user) {
        return ApiResponse.ok(service.detail(user.userId(), orderNo));
    }

    /** 取消用户自己的待支付订单，并同步释放所有店铺子订单库存。 */
    @PostMapping("/{orderNo}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<Void> cancel(@PathVariable String orderNo,
                                    @CurrentUser AuthenticatedUser user) {
        service.cancel(user.userId(), orderNo);
        return ApiResponse.ok(null);
    }

    /** 店铺提交物流信息并发货；所属店铺由员工登录账号绑定。 */
    @PostMapping("/{orderNo}/ship")
    @PreAuthorize("hasRole('STORE_EMPLOYEE')")
    public ApiResponse<LogisticsRecord> ship(@PathVariable String orderNo,
                                             @CurrentUser AuthenticatedUser user,
                                             @Valid @RequestBody ShipRequest request) {
        return ApiResponse.ok(service.ship(user.requireStoreId(), orderNo, request.company(), request.trackingNo()));
    }

    /** 店铺订单工作台只读取当前店铺的子订单。 */
    @GetMapping("/store")
    @PreAuthorize("hasRole('STORE_EMPLOYEE')")
    public ApiResponse<OrderService.StoreOrderPage> storeOrders(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.Min(0) int page,
            @RequestParam(defaultValue = "50") @jakarta.validation.constraints.Min(1) int size) {
        return ApiResponse.ok(service.storeOrdersPage(user.requireStoreId(), page, size));
    }

    /**
     * 单笔订单参数。userId 仅为旧客户端的兼容字段，控制器始终用登录账号覆盖它。
     */
    public record CreateOrderRequest(String userId, @NotNull Long skuId, @NotNull Integer quantity, @NotNull BigDecimal amount) {}

    /** 店铺发货所需承运商和运单编号。 */
    public record ShipRequest(@NotBlank String company, @NotBlank String trackingNo) {}
}
