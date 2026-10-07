package com.xinghe.trade.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/** 保存店铺子订单中的 SKU、数量和下单时价格快照。 */
@TableName("trade_order_item")
public class TradeOrderItem {
    /** 自增明细主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属店铺子订单编号。 */
    private String orderNo;
    /** 商品 SKU。 */
    private Long skuId;
    /** 下单数量。 */
    private Integer quantity;
    /** 下单时的商品单价快照。 */
    private BigDecimal unitPrice;
    /** 下单时计算的明细金额快照。 */
    private BigDecimal lineAmount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getLineAmount() { return lineAmount; }
    public void setLineAmount(BigDecimal lineAmount) { this.lineAmount = lineAmount; }
}
