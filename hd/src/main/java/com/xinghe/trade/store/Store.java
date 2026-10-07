package com.xinghe.trade.store;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 映射平台中的店铺及其状态信息。 */
@TableName("store")
public class Store {
    /** 店铺主键。 */
    @TableId private Long id;
    /** 店铺展示名称。 */
    private String name;
    /** 店铺状态，例如营业或停业。 */
    private String status;
    /** 店铺记录创建时间。 */
    private LocalDateTime createdAt;
    /** 店铺记录最近更新时间。 */
    private LocalDateTime updatedAt;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
