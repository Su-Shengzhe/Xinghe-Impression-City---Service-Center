package com.xinghe.trade.customer;

import com.xinghe.trade.auth.AccountUserService;
import com.xinghe.trade.auth.SecurityConfig;
import com.xinghe.trade.logistics.LogisticsQueryService;
import com.xinghe.trade.product.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies L4 customer endpoints use the authenticated identity and preserve public product browsing. */
@WebMvcTest(CustomerServiceController.class)
@AutoConfigureMockMvc
@ContextConfiguration(classes = CustomerServiceAuthenticationHttpTest.TestApplication.class)
class CustomerServiceAuthenticationHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerConversationService conversationService;

    @MockBean
    private OrderQueryService orderQueryService;

    @MockBean
    private LogisticsQueryService logisticsQueryService;

    @MockBean
    private ProductQueryService productQueryService;

    @MockBean
    private AccountUserService accountUserService;

    @Test
    void anonymousCustomerDataRequestsReturnJson401EvenWithForgedUserHeader() throws Exception {
        mockMvc.perform(get("/api/customer-service/sessions/session-1")
                        .header("X-User-Id", "attacker"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(-1))
                .andExpect(jsonPath("$.message").value("请先登录"));
    }

    @Test
    @WithMockUser(username = "user-authenticated", roles = "CUSTOMER")
    void sessionCreationUsesAuthenticatedUserRatherThanForgedHeader() throws Exception {
        mockMvc.perform(post("/api/customer-service/sessions")
                        .with(csrf())
                        .header("X-User-Id", "attacker"))
                .andExpect(status().isOk());

        verify(conversationService).createSession("user-authenticated");
    }

    @Test
    @WithMockUser(username = "user-authenticated", roles = "CUSTOMER")
    void customerOrderLookupUsesAuthenticatedUserRatherThanForgedHeader() throws Exception {
        mockMvc.perform(get("/api/customer-service/orders/order-1")
                        .header("X-User-Id", "attacker"))
                .andExpect(status().isOk());

        verify(orderQueryService).query("user-authenticated", "order-1");
    }

    @Test
    @WithMockUser(username = "user-authenticated", roles = "CUSTOMER")
    void conversationHistoryUsesAuthenticatedUserRatherThanForgedHeader() throws Exception {
        mockMvc.perform(get("/api/customer-service/sessions/session-1/messages")
                        .header("X-User-Id", "attacker"))
                .andExpect(status().isOk());

        verify(conversationService).history("user-authenticated", "session-1", null);
    }

    @Test
    @WithMockUser(username = "user-authenticated", roles = "CUSTOMER")
    void logisticsLookupUsesAuthenticatedUserRatherThanForgedHeader() throws Exception {
        mockMvc.perform(get("/api/customer-service/orders/order-1/logistics")
                        .header("X-User-Id", "attacker"))
                .andExpect(status().isOk());

        verify(logisticsQueryService).query("user-authenticated", "order-1");
    }

    @Test
    @WithMockUser(username = "employee", roles = "STORE_EMPLOYEE")
    void storeEmployeeCannotAccessCustomerSessionOrOrderEndpoints() throws Exception {
        mockMvc.perform(get("/api/customer-service/sessions/session-1/messages"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/customer-service/orders/order-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousProductSearchRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/customer-service/products")
                        .param("keyword", "shoe"))
                .andExpect(status().isOk());

        verify(productQueryService).search("shoe");
    }

    @Test
    void anonymousProductDetailRemainsPublic() throws Exception {
        mockMvc.perform(get("/api/customer-service/products/10001"))
                .andExpect(status().isOk());

        verify(productQueryService).detail(10001L);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CustomerServiceController.class, SecurityConfig.class})
    static class TestApplication {
    }
}
