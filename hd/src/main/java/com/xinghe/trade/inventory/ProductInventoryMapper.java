package com.xinghe.trade.inventory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 提供商品库存表的基础数据访问操作。 */
@Mapper
public interface ProductInventoryMapper extends BaseMapper<ProductInventory> {
    /** 仅在库存充足时原子锁定数量，避免并发结算超卖。 */
    @Update("UPDATE product_inventory SET available_quantity = available_quantity - #{quantity}, " +
            "locked_quantity = locked_quantity + #{quantity}, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND available_quantity >= #{quantity}")
    int lockAvailable(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /** 支付成功后将锁定库存确认为已售出。 */
    @Update("UPDATE product_inventory SET locked_quantity = locked_quantity - #{quantity}, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND locked_quantity >= #{quantity}")
    int confirmLocked(@Param("skuId") Long skuId, @Param("quantity") int quantity);

    /** 订单取消时将锁定库存退回可售库存。 */
    @Update("UPDATE product_inventory SET available_quantity = available_quantity + #{quantity}, " +
            "locked_quantity = locked_quantity - #{quantity}, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND locked_quantity >= #{quantity}")
    int releaseLocked(@Param("skuId") Long skuId, @Param("quantity") int quantity);
}
