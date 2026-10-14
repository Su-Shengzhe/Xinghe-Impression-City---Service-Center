package com.xinghe.trade.cart;

import com.xinghe.trade.auth.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CartControllerTest {
    /** 验证控制器只把认证上下文中的用户标识传给服务层。 */
    @Test
    void passesHeaderUserToServiceAndNeverAcceptsUserFromRequestBody() {
        CartService service = mock(CartService.class);
        CartService.CartLine line = new CartService.CartLine(10001L, "演示商品", 1L,
                new java.math.BigDecimal("199.00"), 1, new java.math.BigDecimal("199.00"));
        when(service.add(anyString(), eq(10001L), eq(1))).thenReturn(line);
        CartController controller = new CartController(service);

        var response = controller.add(new AuthenticatedUser("user-1", "customer", "CUSTOMER", null),
                new CartController.CartQuantityRequest(10001L, 1));

        verify(service).add("user-1", 10001L, 1);
        assertThat(response.data()).isEqualTo(line);
    }

    /** 验证删除请求将认证用户与路径 SKU 传递给服务层。 */
    @Test
    void removesUsingPathSkuAndCurrentHeaderUser() {
        CartService service = mock(CartService.class);
        CartController controller = new CartController(service);

        controller.remove(new AuthenticatedUser("user-1", "customer", "CUSTOMER", null), 10001L);

        verify(service).remove("user-1", 10001L);
    }

    /** 验证清空请求只使用认证上下文中的当前用户。 */
    @Test
    void clearsUsingCurrentHeaderUser() {
        CartService service = mock(CartService.class);
        CartController controller = new CartController(service);

        controller.clear(new AuthenticatedUser("user-1", "customer", "CUSTOMER", null));

        verify(service).clear("user-1");
    }
}