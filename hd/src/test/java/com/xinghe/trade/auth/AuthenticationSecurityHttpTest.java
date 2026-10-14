package com.xinghe.trade.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinghe.trade.common.CustomerServiceExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc
@ContextConfiguration(classes = AuthenticationSecurityHttpTest.TestApplication.class)
class AuthenticationSecurityHttpTest {
    private static final String PASSWORD = "CustomerPass-2026!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AccountUserService accountUserService;

    @BeforeEach
    void configureUsers() {
        AccountUser customer = account("user-customer", "customer", "CUSTOMER", null, PASSWORD);
        AccountUser employee = account("user-employee", "employee", AccountUserService.EMPLOYEE_ROLE, 7L, PASSWORD);
        when(accountUserService.loadActive(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "customer" -> customer;
            case "employee" -> employee;
            default -> throw new org.springframework.security.core.userdetails.UsernameNotFoundException("Invalid credentials");
        });
        when(accountUserService.findById(anyString())).thenAnswer(invocation -> switch (invocation.getArgument(0, String.class)) {
            case "user-customer" -> customer;
            case "user-employee" -> employee;
            default -> null;
        });
        when(accountUserService.view(org.mockito.ArgumentMatchers.any(AccountUser.class))).thenAnswer(invocation -> {
            AccountUser account = invocation.getArgument(0);
            return new AuthDtos.AccountView(account.getUserId(), account.getUsername(), account.getRole(), account.getStoreId());
        });
    }

    @Test
    void anonymousProtectedRequestReturnsJson401EvenWithForgedIdentityHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header("X-User-Id", "user-attacker")
                        .header("X-Store-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(-1)))
                .andExpect(jsonPath("$.message", equalTo("请先登录")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void csrfEndpointReturnsTokenAndMatchingReadableCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(jsonPath("$.code", equalTo(0)))
                .andReturn();

        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertNotNull(cookie);
        org.junit.jupiter.api.Assertions.assertEquals(body.path("data").asText(), cookie.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(cookie.isHttpOnly());
    }

    @Test
    void missingCsrfTokenReturnsJson403BeforeRegistrationHandler() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-customer\",\"password\":\"CustomerPass-2026!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", equalTo(-1)))
                .andExpect(jsonPath("$.message", equalTo("无权访问")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void csrfCookieAndHeaderAllowAnonymousRegistration() throws Exception {
        AccountUser registered = account("user-new", "new-customer", "CUSTOMER", null, PASSWORD);
        when(accountUserService.registerCustomer("new-customer", PASSWORD)).thenReturn(registered);

        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);

        mockMvc.perform(post("/api/auth/register")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-customer\",\"password\":\"CustomerPass-2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(0)))
                .andExpect(jsonPath("$.data.username", equalTo("new-customer")));
    }

    @Test
    void jsonLoginStoresSecurityContextInSessionAndIgnoresForgedHeaders() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .header("X-User-Id", "user-attacker")
                        .header("X-Store-Id", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"customer\",\"password\":\"CustomerPass-2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username", equalTo("customer")))
                .andExpect(jsonPath("$.data.role", equalTo("CUSTOMER")))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(session);

        mockMvc.perform(get("/api/auth/me")
                        .session(session)
                        .cookie(csrfCookie)
                        .header("X-User-Id", "user-attacker")
                        .header("X-Store-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username", equalTo("customer")))
                .andExpect(jsonPath("$.data.userId", equalTo("user-customer")));
    }

    @Test
    void invalidJsonCredentialsReturnUniformJson401() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"customer\",\"password\":\"WrongPass-2026!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", equalTo(-1)))
                .andExpect(jsonPath("$.message", equalTo("用户名或密码错误")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void malformedLoginJsonReturnsJson400() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);

        mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", equalTo(-1)))
                .andExpect(jsonPath("$.message", equalTo("请求参数无效")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void reloginRotatesExistingSessionId() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);

        MvcResult firstLogin = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"customer\",\"password\":\"CustomerPass-2026!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) firstLogin.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(session);
        String sessionIdBeforeRelogin = session.getId();

        MvcResult secondLogin = mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"employee\",\"password\":\"CustomerPass-2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", equalTo("STORE_EMPLOYEE")))
                .andReturn();
        MockHttpSession rotatedSession = (MockHttpSession) secondLogin.getRequest().getSession(false);
        org.junit.jupiter.api.Assertions.assertNotNull(rotatedSession);
        org.junit.jupiter.api.Assertions.assertNotEquals(sessionIdBeforeRelogin, rotatedSession.getId());
    }

    @Test
    void disabledAccountInvalidatesExistingSession() throws Exception {
        MockHttpSession session = loginSession("customer");
        AccountUser disabled = account("user-customer", "customer", "CUSTOMER", null, PASSWORD);
        disabled.setStatus("DISABLED");
        when(accountUserService.findById("user-customer")).thenReturn(disabled);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", equalTo("账号信息已变更，请重新登录")));
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());
    }

    @Test
    void roleOrStoreChangeInvalidatesExistingEmployeeSession() throws Exception {
        MockHttpSession session = loginSession("employee");
        AccountUser moved = account("user-employee", "employee", AccountUserService.EMPLOYEE_ROLE, 8L, PASSWORD);
        when(accountUserService.findById("user-employee")).thenReturn(moved);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertTrue(session.isInvalid());

        MockHttpSession secondSession = loginSession("employee");
        AccountUser demoted = account("user-employee", "employee", "CUSTOMER", 7L, PASSWORD);
        when(accountUserService.findById("user-employee")).thenReturn(demoted);
        mockMvc.perform(get("/api/auth/me").session(secondSession))
                .andExpect(status().isUnauthorized());
        org.junit.jupiter.api.Assertions.assertTrue(secondSession.isInvalid());
    }

    @Test
    void accountLookupOutageFailsClosedWithoutDestroyingSession() throws Exception {
        MockHttpSession session = loginSession("customer");
        when(accountUserService.findById("user-customer")).thenThrow(new IllegalStateException("database unavailable"));

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message", equalTo("账户校验暂不可用，请稍后重试")));
        org.junit.jupiter.api.Assertions.assertFalse(session.isInvalid());
    }

    private MockHttpSession loginSession(String username) throws Exception {
        MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andReturn();
        Cookie csrfCookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        org.junit.jupiter.api.Assertions.assertNotNull(csrfCookie);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @Test
    @WithMockUser(username = "customer", roles = "CUSTOMER")
    void authenticatedCustomerRoleIsExposedFromVerifiedPrincipal() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", equalTo("CUSTOMER")));
    }

    @Test
    @WithMockUser(username = "employee", roles = "STORE_EMPLOYEE")
    void authenticatedEmployeeRoleAndStoreAreExposedFromVerifiedPrincipal() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role", equalTo("STORE_EMPLOYEE")))
                .andExpect(jsonPath("$.data.storeId", equalTo(7)));
    }

    @Test
    @WithMockUser(username = "customer", roles = "CUSTOMER")
    void authenticatedPostWithoutCsrfReturnsJson403() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", equalTo(-1)))
                .andExpect(jsonPath("$.message", equalTo("无权访问")))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static AccountUser account(String userId, String username, String role, Long storeId, String password) {
        AccountUser account = new AccountUser();
        account.setUserId(userId);
        account.setUsername(username);
        account.setPasswordHash(new BCryptPasswordEncoder().encode(password));
        account.setRole(role);
        account.setStatus("ACTIVE");
        account.setStoreId(storeId);
        return account;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({AuthController.class, SecurityConfig.class, CustomerServiceExceptionHandler.class})
    static class TestApplication {}
}
