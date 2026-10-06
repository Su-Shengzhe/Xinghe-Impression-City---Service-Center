package com.xinghe.trade.cart;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xinghe.trade.product.Product;
import com.xinghe.trade.product.ProductMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CartService {
    private static final int MAX_QUANTITY = 99;

    private final CartItemMapper cartMapper;
    private final ProductMapper productMapper;

    public CartService(CartItemMapper cartMapper, ProductMapper productMapper) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
    }

    public List<CartLine> list(String userId) {
        String normalizedUserId = requireUser(userId);
        List<CartItem> items = cartMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, normalizedUserId)
                .orderByDesc(CartItem::getUpdatedAt));
        if (items.isEmpty()) return List.of();

        List<Long> skuIds = items.stream().map(CartItem::getSkuId).distinct().toList();
        Map<Long, Product> products = productMapper.selectBatchIds(skuIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        return items.stream().map(item -> toLine(item, products.get(item.getSkuId()))).toList();
    }

    @Transactional
    public CartLine add(String userId, Long skuId, Integer quantity) {
        String normalizedUserId = requireUser(userId);
        validateSkuId(skuId);
        validateQuantity(quantity);
        Product product = requireOnSaleProduct(skuId);
        cartMapper.addQuantity(normalizedUserId, skuId, quantity);
        CartItem item = findItem(normalizedUserId, skuId);
        if (item.getQuantity() > MAX_QUANTITY) {
            throw new IllegalArgumentException("购物车单个商品数量不能超过 " + MAX_QUANTITY);
        }
        return toLine(item, product);
    }

    @Transactional
    public CartLine setQuantity(String userId, Long skuId, Integer quantity) {
        String normalizedUserId = requireUser(userId);
        validateSkuId(skuId);
        validateQuantity(quantity);
        Product product = requireOnSaleProduct(skuId);
        int updated = cartMapper.update(null, new UpdateWrapper<CartItem>()
                .eq("user_id", normalizedUserId)
                .eq("sku_id", skuId)
                .set("quantity", quantity)
                .set("updated_at", LocalDateTime.now()));
        if (updated == 0) throw new IllegalArgumentException("购物车中不存在该商品");
        return toLine(findItem(normalizedUserId, skuId), product);
    }

    public void remove(String userId, Long skuId) {
        String normalizedUserId = requireUser(userId);
        validateSkuId(skuId);
        int deleted = cartMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, normalizedUserId)
                .eq(CartItem::getSkuId, skuId));
        if (deleted == 0) throw new IllegalArgumentException("购物车中不存在该商品");
    }

    private CartItem findItem(String userId, Long skuId) {
        CartItem item = cartMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, skuId));
        if (item == null) throw new IllegalStateException("购物车数据未能保存");
        return item;
    }

    private Product requireOnSaleProduct(Long skuId) {
        Product product = productMapper.selectById(skuId);
        if (product == null || !"ON_SALE".equals(product.getStatus())) {
            throw new IllegalArgumentException("商品不存在或已下架");
        }
        return product;
    }

    private CartLine toLine(CartItem item, Product product) {
        if (product == null) {
            return new CartLine(item.getSkuId(), null, null, null, item.getQuantity(), null);
        }
        return new CartLine(item.getSkuId(), product.getName(), product.getStoreId(), product.getPrice(),
                item.getQuantity(), product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
    }

    private String requireUser(String userId) {
        if (!StringUtils.hasText(userId)) throw new IllegalArgumentException("X-User-Id 请求头不能为空");
        return userId.trim();
    }

    private void validateSkuId(Long skuId) {
        if (skuId == null || skuId <= 0) throw new IllegalArgumentException("商品 SKU 必须为正整数");
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("商品数量必须在 1 到 " + MAX_QUANTITY + " 之间");
        }
    }

    public record CartLine(Long skuId, String productName, Long storeId, BigDecimal unitPrice,
                           Integer quantity, BigDecimal lineAmount) {}
}