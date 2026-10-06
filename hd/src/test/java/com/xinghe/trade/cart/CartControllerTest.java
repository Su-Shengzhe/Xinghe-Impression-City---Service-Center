package com.xinghe.trade.cart;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CartControllerTest {
    @Test
    void passesHeaderUserToServiceAndNeverAcceptsUserFromRequestBody() {
        CartService service = mock(CartService.class);
        CartService.CartLine line = new CartService.CartLine(10001L, "演示商品", 1L,
                new java.math.BigDecimal("199.00"), 1, new java.math.BigDecimal("199.00"));
        when(service.add(anyString(), eq(10001L), eq(1))).thenReturn(line);
        CartController controller = new CartController(service);

        var response = controller.add(" user-1 ", new CartController.CartQuantityRequest(10001L, 1));

        verify(service).add(" user-1 ", 10001L, 1);
        assertThat(response.data()).isEqualTo(line);
    }

    @Test
    void removesUsingPathSkuAndCurrentHeaderUser() {
        CartService service = mock(CartService.class);
        CartController controller = new CartController(service);

        controller.remove("user-1", 10001L);

        verify(service).remove("user-1", 10001L);
    }
}