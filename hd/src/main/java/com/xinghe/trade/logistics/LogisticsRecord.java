package com.xinghe.trade.logistics;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 映射店铺子订单对应的承运商、运单和物流状态。 */
@TableName("logistics_record")
public class LogisticsRecord {
    /** 物流记录自增主键。 */
    @TableId
    private Long id;
    /** 被发货店铺子订单编号，每个子订单至多一条发货记录。 */
    private String orderNo;
    /** 承运商名称。 */
    private String company;
    /** 承运商运单编号。 */
    private String trackingNo;
    /** 当前物流状态。 */
    private String status;
    /** 面向查询方的物流描述。 */
    private String description;
    /** 物流信息最近更新时间。 */
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getTrackingNo() { return trackingNo; }
    public void setTrackingNo(String trackingNo) { this.trackingNo = trackingNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
