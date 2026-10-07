package com.xinghe.trade.order;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 映射交易订单记录及其商品、金额和状态快照。 */
@TableName("trade_order")
public class TradeOrder {
    /** 订单唯一编号。 */
    @TableId
    private String orderNo;
    /** 多店铺场景下关联的父订单编号；独立订单可为空。 */
    private String parentOrderNo;
    /** 下单用户标识。 */
    private String userId;
    /** 此订单所属店铺。 */
    private Long storeId;
    /** 订单对应的商品 SKU。 */
    private Long skuId;
    /** 下单商品数量。 */
    private Integer quantity;
    /** 下单时记录的订单金额。 */
    private BigDecimal amount;
    /** 订单当前业务状态。 */
    private String status;
    /** 订单创建时间。 */
    private LocalDateTime createdAt;
    /** 订单最近更新时间。 */
    private LocalDateTime updatedAt;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getParentOrderNo() { return parentOrderNo; }
    public void setParentOrderNo(String parentOrderNo) { this.parentOrderNo = parentOrderNo; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
