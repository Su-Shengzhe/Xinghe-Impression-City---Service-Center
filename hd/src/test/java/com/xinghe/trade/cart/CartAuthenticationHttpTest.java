package com.xinghe.trade.cart;

import com.xinghe.trade.auth.AccountUserService;
import com.xinghe.trade.auth.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/** Verifies customer business identity comes from the authenticated principal, not X-User-Id. */
@WebMvcTest(CartController.class)
@AutoConfigureMockMvc
@ContextConfiguration(classes = CartAuthenticationHttpTest.TestApplication.class)
class CartAuthenticationHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CartService cartService;

    @MockBean
    private AccountUserService accountUserService;

    @Test
    @WithMockUser(username = "customer", roles = "CUSTOMER")
    void forgedUserHeaderCannotChangeCartOwner() throws Exception {
        mockMvc.perform(get("/api/cart")
                        .with(csrf())
                        .header("X-User-Id", "attacker"))
                .andExpect(status().isOk());

        verify(cartService).list("customer");
    }

    @Test
    @WithMockUser(username = "employee", roles = "STORE_EMPLOYEE")
    void storeEmployeeCannotAccessCustomerCart() throws Exception {
        mockMvc.perform(get("/api/cart").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CartController.class, SecurityConfig.class})
    static class TestApplication {
    }
}