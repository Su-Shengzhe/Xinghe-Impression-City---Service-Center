package com.xinghe.trade.cart;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xinghe.trade.product.Product;
import com.xinghe.trade.product.ProductMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CartServiceTest {
    /** 验证添加数量并使用商品服务端价格计算行金额。 */
    @Test
    void addsQuantityForCurrentUserAndReturnsServerProductPrice() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        Product product = product("ON_SALE", "199.00");
        when(productMapper.selectById(10001L)).thenReturn(product);
        CartItem item = item("user-1", 10001L, 2);
        when(cartMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(item);

        CartService service = new CartService(cartMapper, productMapper);
        CartService.CartLine result = service.add(" user-1 ", 10001L, 2);

        verify(cartMapper).addQuantity("user-1", 10001L, 2);
        assertThat(result.productName()).isEqualTo("演示商品");
        assertThat(result.unitPrice()).isEqualByComparingTo("199.00");
        assertThat(result.quantity()).isEqualTo(2);
        assertThat(result.lineAmount()).isEqualByComparingTo("398.00");
    }

    /** 验证下架商品不会写入购物车。 */
    @Test
    void rejectsOffSaleProductBeforeChangingCart() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        when(productMapper.selectById(10001L)).thenReturn(product("OFF_SALE", "199.00"));

        CartService service = new CartService(cartMapper, productMapper);

        assertThatThrownBy(() -> service.add("user-1", 10001L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("商品不存在或已下架");
        verifyNoInteractions(cartMapper);
    }

    /** 验证无效数量在访问商品和购物车数据前被拒绝。 */
    @Test
    void rejectsInvalidQuantityBeforeChangingCart() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        CartService service = new CartService(cartMapper, productMapper);

        assertThatThrownBy(() -> service.add("user-1", 10001L, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("商品数量必须在 1 到 99 之间");
        verifyNoInteractions(productMapper, cartMapper);
    }

    /** 验证查询结果仅由当前用户购物车记录及商品数据组成。 */
    @Test
    void listsOnlyItemsReturnedForCurrentUserAndUsesProductData() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        when(cartMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(java.util.List.of(item("user-1", 10001L, 1)));
        when(productMapper.selectBatchIds(java.util.List.of(10001L)))
                .thenReturn(java.util.List.of(product("ON_SALE", "199.00")));

        CartService service = new CartService(cartMapper, productMapper);
        var result = service.list("user-1");

        assertThat(result).singleElement().satisfies(line -> {
            assertThat(line.skuId()).isEqualTo(10001L);
            assertThat(line.quantity()).isEqualTo(1);
        });
        verify(cartMapper).selectList(any(LambdaQueryWrapper.class));
    }

    /** 验证不存在的购物车商品不能被改量。 */
    @Test
    void doesNotAllowUpdatingMissingItem() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        when(productMapper.selectById(10001L)).thenReturn(product("ON_SALE", "199.00"));
        when(cartMapper.update(isNull(), any())).thenReturn(0);

        CartService service = new CartService(cartMapper, productMapper);

        assertThatThrownBy(() -> service.setQuantity("user-1", 10001L, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("购物车中不存在该商品");
    }

    /** 验证清空购物车仅删除当前用户的购物车记录。 */
    @Test
    void clearsOnlyCurrentUserCart() {
        CartItemMapper cartMapper = mock(CartItemMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        CartService service = new CartService(cartMapper, productMapper);

        service.clear(" user-1 ");

        verify(cartMapper).delete(any(LambdaQueryWrapper.class));
        verifyNoInteractions(productMapper);
    }

    /** 构造测试使用的商品数据。 */
    private Product product(String status, String price) {
        Product product = new Product();
        product.setId(10001L);
        product.setStoreId(1L);
        product.setName("演示商品");
        product.setPrice(new BigDecimal(price));
        product.setStatus(status);
        return product;
    }

    /** 构造测试使用的购物车记录。 */
    private CartItem item(String userId, Long skuId, int quantity) {
        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }
}