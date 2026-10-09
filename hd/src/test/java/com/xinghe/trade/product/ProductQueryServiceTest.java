package com.xinghe.trade.product;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductQueryServiceTest {
    @Test
    void returnsMysqlResultsWhenElasticsearchIsDisabled() {
        ProductMapper mapper = mock(ProductMapper.class);
        Product product = product();
        when(mapper.selectList(any())).thenReturn(List.of(product));
        ProductQueryService service = new ProductQueryService(mapper, Optional.empty());

        assertThat(service.search("商品")).containsExactly(product);
        verify(mapper).selectList(any());
    }

    @Test
    void fallsBackToMysqlWhenElasticsearchFails() {
        ProductMapper mapper = mock(ProductMapper.class);
        ProductDocumentRepository repository = mock(ProductDocumentRepository.class);
        Product product = product();
        when(repository.findTop20ByStatusAndNameContainingOrStatusAndDescriptionContainingOrderByUpdatedAtDesc(
                "ON_SALE", "商品", "ON_SALE", "商品")).thenThrow(new IllegalStateException("ES unavailable"));
        when(mapper.selectList(any())).thenReturn(List.of(product));
        ProductQueryService service = new ProductQueryService(mapper, Optional.of(repository));
        ReflectionTestUtils.setField(service, "elasticsearchEnabled", true);

        assertThat(service.search("商品")).containsExactly(product);
        verify(mapper).selectList(any());
    }

    @Test
    void mapsElasticsearchResultsToProducts() {
        ProductMapper mapper = mock(ProductMapper.class);
        ProductDocumentRepository repository = mock(ProductDocumentRepository.class);
        ProductDocument document = new ProductDocument(product());
        when(repository.findTop20ByStatusAndNameContainingOrStatusAndDescriptionContainingOrderByUpdatedAtDesc(
                "ON_SALE", "商品", "ON_SALE", "商品")).thenReturn(List.of(document));
        ProductQueryService service = new ProductQueryService(mapper, Optional.of(repository));
        ReflectionTestUtils.setField(service, "elasticsearchEnabled", true);

        assertThat(service.search("商品")).singleElement().satisfies(found -> {
            assertThat(found.getId()).isEqualTo(100L);
            assertThat(found.getName()).isEqualTo("商品");
        });
        verifyNoInteractions(mapper);
    }

    private Product product() {
        Product product = new Product();
        product.setId(100L);
        product.setStoreId(5L);
        product.setName("商品");
        product.setPrice(new BigDecimal("12.50"));
        product.setStatus("ON_SALE");
        return product;
    }
}