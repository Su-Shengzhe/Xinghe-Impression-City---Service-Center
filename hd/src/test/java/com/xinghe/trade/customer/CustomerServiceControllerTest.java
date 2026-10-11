package com.xinghe.trade.customer;

import com.xinghe.trade.common.CustomerServiceExceptionHandler;
import com.xinghe.trade.logistics.LogisticsQueryService;
import com.xinghe.trade.product.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 验证客服商品详情接口的路径参数校验和统一错误响应。 */
class CustomerServiceControllerTest {
    @Test
    void rejectsNonPositiveProductDetailIdBeforeQueryingProduct() throws Exception {
        ProductQueryService productQueryService = mock(ProductQueryService.class);
        CustomerServiceController controller = new CustomerServiceController(
                mock(CustomerConversationService.class),
                mock(OrderQueryService.class),
                mock(LogisticsQueryService.class),
                productQueryService);
        MockMvc mockMvc = standaloneSetup(controller)
                .setControllerAdvice(new CustomerServiceExceptionHandler())
                .build();

        mockMvc.perform(get("/api/customer-service/products/0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":-1,\"message\":\"请求参数无效\",\"data\":null}"));

        verifyNoInteractions(productQueryService);
    }
}