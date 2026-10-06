package com.xinghe.trade.inventory;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("product_inventory")
public class ProductInventory {
    @TableId private Long skuId;
    private Integer availableQuantity;
    private Integer lockedQuantity;
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
