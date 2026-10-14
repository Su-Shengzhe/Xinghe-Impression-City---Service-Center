package com.xinghe.trade.product;

import com.xinghe.trade.auth.AccountUser;
import com.xinghe.trade.auth.AccountUserPrincipal;
import com.xinghe.trade.auth.AccountUserService;
import com.xinghe.trade.auth.SecurityConfig;
import com.xinghe.trade.common.CustomerServiceExceptionHandler;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies L5 product and index operations are employee-only and store-bound. */
@WebMvcTest({ProductManagementController.class, ProductIndexController.class})
@AutoConfigureMockMvc
@ContextConfiguration(classes = StoreProductAuthorizationHttpTest.TestApplication.class)
@org.springframework.test.context.TestPropertySource(properties = {"xinghe.search.elasticsearch.enabled=true", "xinghe.search.rebuild-api-enabled=true"})
class StoreProductAuthorizationHttpTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductManagementService productManagementService;

    @MockBean
    private ProductIndexService productIndexService;

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
    void anonymousProductManagementRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/store/products"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotAccessProductManagementOrRebuildIndex() throws Exception {
        mockMvc.perform(get("/api/store/products").with(authentication(customerAuthentication())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/store/products/rebuild-index")
                        .with(authentication(customerAuthentication()))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(productManagementService, productIndexService);
    }

    @Test
    void employeeStoreBindingWinsOverForgedHeader() throws Exception {
        mockMvc.perform(get("/api/store/products")
                        .with(authentication(employeeAuthentication(7L)))
                        .header("X-Store-Id", "999"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/store/products/rebuild-index")
                        .with(authentication(employeeAuthentication(7L)))
                        .with(csrf()))
                .andExpect(status().isOk());

        verify(productManagementService).listPage(7L, 0, 50);
        verify(productIndexService).rebuild();
    }

    @Test
    void employeeWithoutStoreBindingIsForbiddenAndDoesNotReachService() throws Exception {
        when(accountUserService.findById("employee-1")).thenReturn(account("employee-1", "employee", "STORE_EMPLOYEE", null));
        mockMvc.perform(get("/api/store/products")
                        .with(authentication(employeeAuthentication(null))))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code", equalTo(-1)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message", equalTo("无权操作该店铺")));

        verifyNoInteractions(productManagementService);
    }

    @Test
    void unsupportedAuthenticatedPrincipalReturnsJson401() throws Exception {
        UsernamePasswordAuthenticationToken unsupportedPrincipal = new UsernamePasswordAuthenticationToken(
                new Object(), "credentials", java.util.List.of(new SimpleGrantedAuthority("ROLE_STORE_EMPLOYEE")));

        mockMvc.perform(get("/api/store/products").with(authentication(unsupportedPrincipal)))
                .andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code", equalTo(-1)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message", equalTo("请先登录")));

        verifyNoInteractions(productManagementService);
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
    @Import({ProductManagementController.class, ProductIndexController.class, SecurityConfig.class,
            CustomerServiceExceptionHandler.class})
    static class TestApplication {
    }
}
