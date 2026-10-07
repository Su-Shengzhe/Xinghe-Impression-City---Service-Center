package com.xinghe.trade.product;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 映射商品目录记录，商品价格和上下架状态均由服务端维护。 */
@TableName("product")
public class Product {
    /** 商品 SKU 主键。 */
    @TableId
    private Long id;
    /** 商品所属店铺，用于结算拆分和店铺操作授权。 */
    private Long storeId;
    /** 商品展示名称。 */
    private String name;
    /** 商品描述，可为空。 */
    private String description;
    /** 当前销售单价；订单明细会保存下单时价格快照。 */
    private BigDecimal price;
    /** 商品状态，管理接口使用 ON_SALE 或 OFF_SALE。 */
    private String status;
    /** 商品创建时间。 */
    private LocalDateTime createdAt;
    /** 商品最近修改时间。 */
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
