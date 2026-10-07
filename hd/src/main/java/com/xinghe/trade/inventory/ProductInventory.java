package com.xinghe.trade.inventory;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 映射 SKU 库存及已锁定库存数量。 */
@TableName("product_inventory")
public class ProductInventory {
    /** 对应商品 SKU，同时作为库存记录主键。 */
    @TableId private Long skuId;
    /** 当前可供新订单占用的库存数量。 */
    private Integer availableQuantity;
    /** 已为未完成订单预留、尚未扣减的库存数量。 */
    private Integer lockedQuantity;
    /** 库存记录最近更新时间。 */
    private LocalDateTime updatedAt;
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getAvailableQuantity() { return availableQuantity; }
    public void setAvailableQuantity(Integer availableQuantity) { this.availableQuantity = availableQuantity; }
    public Integer getLockedQuantity() { return lockedQuantity; }
    public void setLockedQuantity(Integer lockedQuantity) { this.lockedQuantity = lockedQuantity; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
