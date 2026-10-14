package com.xinghe.trade.order;

import com.xinghe.trade.auth.AccountUser;
import com.xinghe.trade.auth.AccountUserPrincipal;
import com.xinghe.trade.auth.AccountUserService;
import com.xinghe.trade.auth.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies L5 store order queries and shipping use the employee's bound store. */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc
@ContextConfiguration(classes = StoreOrderAuthorizationHttpTest.TestApplication.class)
class StoreOrderAuthorizationHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private AccountUserService accountUserService;

    @BeforeEach
    void currentAccounts() {
        when(accountUserService.findById("customer-1")).thenReturn(account("customer-1", "customer", "CUSTOMER", null));
        when(accountUserService.findById("employee-1")).thenReturn(account("employee-1", "employee", "STORE_EMPLOYEE", 7L));
    }

    private static AccountUser account(String id, String username, String role, Long storeId) {
        AccountUser value = new AccountUser();
        value.setUserId(id);
        value.setUsername(username);
        value.setPasswordHash("hash");
        value.setRole(role);
        value.setStatus("ACTIVE");
        value.setStoreId(storeId);
        return value;
    }

    @Test
    void customerCannotReadStoreOrdersOrShip() throws Exception {
        mockMvc.perform(get("/api/orders/store").with(authentication(customerAuthentication())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders/child-1/ship")
                        .with(authentication(customerAuthentication()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"carrier\",\"trackingNo\":\"TRACK-1\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void employeeCannotUseCustomerOrderOperations() throws Exception {
        mockMvc.perform(post("/api/orders/checkout")
                        .with(authentication(employeeAuthentication(7L)))
                        .with(csrf())
                        .header("Idempotency-Token", "employee-checkout"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders/order-1/pay")
                        .with(authentication(employeeAuthentication(7L)))
                        .with(csrf())
                        .header("Idempotency-Token", "employee-token"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(orderService);
    }

    @Test
    void employeeStoreBindingWinsOverForgedHeaderForOrders() throws Exception {
        mockMvc.perform(get("/api/orders/store")
                        .with(authentication(employeeAuthentication(7L)))
                        .header("X-Store-Id", "999"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/child-1/ship")
                        .with(authentication(employeeAuthentication(7L)))
                        .with(csrf())
                        .header("X-Store-Id", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"carrier\",\"trackingNo\":\"TRACK-1\"}"))
                .andExpect(status().isOk());

        verify(orderService).storeOrdersPage(7L, 0, 50);
        verify(orderService).ship(7L, "child-1", "carrier", "TRACK-1");
    }

    private static UsernamePasswordAuthenticationToken customerAuthentication() {
        AccountUser account = new AccountUser();
        account.setUserId("customer-1");
        account.setUsername("customer");
        account.setPasswordHash("hash");
        account.setRole("CUSTOMER");
        account.setStatus("ACTIVE");
        AccountUserPrincipal principal = new AccountUserPrincipal(account);
        return new UsernamePasswordAuthenticationToken(principal, principal.getPassword(), principal.getAuthorities());
    }

    private static UsernamePasswordAuthenticationToken employeeAuthentication(Long storeId) {
        AccountUser account = new AccountUser();
        account.setUserId("employee-1");
        account.setUsername("employee");
        account.setPasswordHash("hash");
        account.setRole(AccountUserService.EMPLOYEE_ROLE);
        account.setStatus("ACTIVE");
        account.setStoreId(storeId);
        AccountUserPrincipal principal = new AccountUserPrincipal(account);
        return new UsernamePasswordAuthenticationToken(principal, principal.getPassword(), principal.getAuthorities());
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({OrderController.class, SecurityConfig.class})
    static class TestApplication {
    }
}
