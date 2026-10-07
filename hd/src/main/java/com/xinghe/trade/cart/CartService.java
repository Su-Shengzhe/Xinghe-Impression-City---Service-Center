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

/** 实现按用户隔离的购物车查询、添加、改量与删除规则。 */
@Service
public class CartService {
    /** 单个 SKU 在购物车中允许的最大数量。 */
    private static final int MAX_QUANTITY = 99;

    private final CartItemMapper cartMapper;
    private final ProductMapper productMapper;

    /** 注入购物车和商品数据访问对象。 */
    public CartService(CartItemMapper cartMapper, ProductMapper productMapper) {
        this.cartMapper = cartMapper;
        this.productMapper = productMapper;
    }

    /** 查询指定用户的购物车，并使用商品表中的最新商品信息组装结果。 */
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

    /** 校验商品与数量后，将数量原子累加到指定用户的购物车。 */
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

    /** 将指定用户购物车中商品的数量改为给定值。 */
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

    /** 删除指定用户购物车中的商品项；商品项不存在时返回业务错误。 */
    public void remove(String userId, Long skuId) {
        String normalizedUserId = requireUser(userId);
        validateSkuId(skuId);
        int deleted = cartMapper.delete(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, normalizedUserId)
                .eq(CartItem::getSkuId, skuId));
        if (deleted == 0) throw new IllegalArgumentException("购物车中不存在该商品");
    }

    /** 按用户和 SKU 查询购物车记录，确保不会读取其他用户的数据。 */
    private CartItem findItem(String userId, Long skuId) {
        CartItem item = cartMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getSkuId, skuId));
        if (item == null) throw new IllegalStateException("购物车数据未能保存");
        return item;
    }

    /** 读取商品并要求其当前处于在售状态。 */
    private Product requireOnSaleProduct(Long skuId) {
        Product product = productMapper.selectById(skuId);
        if (product == null || !"ON_SALE".equals(product.getStatus())) {
            throw new IllegalArgumentException("商品不存在或已下架");
        }
        return product;
    }

    /** 将购物车记录与商品资料合并为响应行，金额使用服务端商品价格计算。 */
    private CartLine toLine(CartItem item, Product product) {
        if (product == null) {
            return new CartLine(item.getSkuId(), null, null, null, item.getQuantity(), null);
        }
        return new CartLine(item.getSkuId(), product.getName(), product.getStoreId(), product.getPrice(),
                item.getQuantity(), product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
    }

    /** 校验并规范化请求头中的用户标识。 */
    private String requireUser(String userId) {
        if (!StringUtils.hasText(userId)) throw new IllegalArgumentException("X-User-Id 请求头不能为空");
        return userId.trim();
    }

    /** 拒绝空值、零值及负数 SKU。 */
    private void validateSkuId(Long skuId) {
        if (skuId == null || skuId <= 0) throw new IllegalArgumentException("商品 SKU 必须为正整数");
    }

    /** 限制购物车商品数量为 1 到最大允许值。 */
    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("商品数量必须在 1 到 " + MAX_QUANTITY + " 之间");
        }
    }

    /** 购物车商品行响应，单价来自商品数据，行金额由单价和数量计算。 */
    public record CartLine(Long skuId, String productName, Long storeId, BigDecimal unitPrice,
                           Integer quantity, BigDecimal lineAmount) {}
}