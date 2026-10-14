package com.xinghe.trade.product;

import com.xinghe.trade.auth.AccountUser;
import com.xinghe.trade.auth.AccountUserPrincipal;
import com.xinghe.trade.auth.AuthenticatedUser;
import com.xinghe.trade.auth.CurrentUserArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 验证商品管理控制器使用认证主体中的店铺归属，而不是请求头。 */
class ProductManagementControllerTest {
    @Test
    void ignoresForgedStoreHeaderWhenResolvingEmployeeStore() throws Exception {
        ProductManagementService service = mock(ProductManagementService.class);
        MockMvc mockMvc = standaloneSetup(new ProductManagementController(service))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver() {
                    @Override
                    public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                                                  org.springframework.web.method.support.ModelAndViewContainer container,
                                                  org.springframework.web.context.request.NativeWebRequest request,
                                                  org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                        return new AuthenticatedUser("employee-1", "employee", "STORE_EMPLOYEE", 5L);
                    }
                })
                .build();

        mockMvc.perform(get("/api/store/products").header("X-Store-Id", "999"))
                .andExpect(status().isOk());

        verify(service).listPage(5L, 0, 50);
    }
}
