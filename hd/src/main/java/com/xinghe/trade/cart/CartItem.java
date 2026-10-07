package com.xinghe.trade.cart;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 映射用户购物车中的单个 SKU 及其数量。 */
@TableName("cart_item")
public class CartItem {
    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 购物车所属用户标识。 */
    private String userId;
    /** 商品 SKU 标识。 */
    private Long skuId;
    /** 用户当前选购数量。 */
    private Integer quantity;
    /** 购物车项创建时间。 */
    private LocalDateTime createdAt;
    /** 购物车项最近更新时间。 */
    private LocalDateTime updatedAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
