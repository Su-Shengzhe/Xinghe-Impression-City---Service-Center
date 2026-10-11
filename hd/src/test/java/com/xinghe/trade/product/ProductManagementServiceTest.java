package com.xinghe.trade.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghe.trade.inventory.ProductInventoryMapper;
import com.xinghe.trade.store.Store;
import com.xinghe.trade.store.StoreMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 覆盖店铺商品管理的归属检查和创建写入行为。 */
class ProductManagementServiceTest {
    /** 确认创建商品时店铺 ID 来自授权上下文，且同时初始化可用库存。 */
    @Test
    void createsProductAndInitialInventoryForOpenStore() {
        ProductMapper products = mock(ProductMapper.class);
        StoreMapper stores = mock(StoreMapper.class);
        ProductInventoryMapper inventory = mock(ProductInventoryMapper.class);
        Store store = new Store();
        store.setId(5L);
        store.setStatus("OPEN");
        when(stores.selectById(5L)).thenReturn(store);
        when(products.insert(any(Product.class))).thenReturn(1);
        when(inventory.insert(any(com.xinghe.trade.inventory.ProductInventory.class))).thenReturn(1);

        ProductManagementService service = new ProductManagementService(products, stores, inventory);
        service.create(5L, new ProductManagementService.ProductRequest(100L, "商品", "描述",
                new BigDecimal("12.50"), 8));

        verify(products).insert(org.mockito.ArgumentMatchers.<Product>argThat(product ->
                product.getStoreId().equals(5L) && product.getStatus().equals("ON_SALE")));
        verify(inventory).insert(org.mockito.ArgumentMatchers.<com.xinghe.trade.inventory.ProductInventory>argThat(stock ->
                stock.getSkuId().equals(100L) && stock.getAvailableQuantity() == 8 && stock.getLockedQuantity() == 0));
    }

    /** 确认商品只能由所属店铺修改，跨店请求在写库前被拒绝。 */
    @Test
    void rejectsEditingAnotherStoresProduct() {
        ProductMapper products = mock(ProductMapper.class);
        StoreMapper stores = mock(StoreMapper.class);
        Store store = new Store();
        store.setId(5L);
        store.setStatus("OPEN");
        when(stores.selectById(5L)).thenReturn(store);
        when(products.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        ProductManagementService service = new ProductManagementService(products, stores, mock(ProductInventoryMapper.class));

        assertThatThrownBy(() -> service.update(5L, 100L, new ProductManagementService.ProductUpdateRequest(
                "商品", "描述", new BigDecimal("12.50"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("店铺商品不存在");
        verify(products, never()).updateById(any(Product.class));
    }

    /** 确认商品编辑只更新资料，不使用占位库存覆盖现有库存。 */
    @Test
    void updatesProductDetailsWithoutTouchingInventory() {
        ProductMapper products = mock(ProductMapper.class);
        StoreMapper stores = mock(StoreMapper.class);
        ProductInventoryMapper inventory = mock(ProductInventoryMapper.class);
        Store store = new Store();
        store.setId(5L);
        store.setStatus("OPEN");
        Product product = new Product();
        product.setId(100L);
        product.setStoreId(5L);
        product.setName("旧商品");
        product.setPrice(new BigDecimal("10.00"));
        when(stores.selectById(5L)).thenReturn(store);
        when(products.selectOne(any(LambdaQueryWrapper.class))).thenReturn(product);
        when(products.updateById(any(Product.class))).thenReturn(1);

        ProductManagementService service = new ProductManagementService(products, stores, inventory);
        service.update(5L, 100L, new ProductManagementService.ProductUpdateRequest(
                "新商品", "新描述", new BigDecimal("12.50")));

        assertThat(product.getName()).isEqualTo("新商品");
        assertThat(product.getDescription()).isEqualTo("新描述");
        assertThat(product.getPrice()).isEqualByComparingTo("12.50");
        verify(products).updateById(product);
        verifyNoInteractions(inventory);
    }
}