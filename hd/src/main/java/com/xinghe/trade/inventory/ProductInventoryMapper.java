package com.xinghe.trade.inventory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/** 提供商品库存表的基础数据访问操作。 */
@Mapper
public interface ProductInventoryMapper extends BaseMapper<ProductInventory> {
    /** 仅在库存充足时原子锁定数量，避免并发结算超卖。 */
    @Update("UPDATE product_inventory SET available_quantity = available_quantity - #{quantity}, " +
            "locked_quantity = locked_quantity + #{quantity}, version = version + 1, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND version = #{version} AND available_quantity >= #{quantity}")
    int lockAvailable(@Param("skuId") Long skuId, @Param("quantity") int quantity, @Param("version") long version);

    /** 兼容阶段 C 调用方；生产服务通过带版本重载执行。 */
    default int lockAvailable(Long skuId, int quantity) { return lockAvailable(skuId, quantity, 0L); }

    @Select("SELECT * FROM product_inventory WHERE sku_id = #{skuId}")
    ProductInventory selectInventory(@Param("skuId") Long skuId);

    /** 支付成功后将锁定库存确认为已售出。 */
    @Update("UPDATE product_inventory SET locked_quantity = locked_quantity - #{quantity}, " +
            "sold_quantity = sold_quantity + #{quantity}, version = version + 1, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND version = #{version} AND locked_quantity >= #{quantity}")
    int confirmLocked(@Param("skuId") Long skuId, @Param("quantity") int quantity, @Param("version") long version);

    default int confirmLocked(Long skuId, int quantity) { return confirmLocked(skuId, quantity, 0L); }

    /** 订单取消时将锁定库存退回可售库存。 */
    @Update("UPDATE product_inventory SET available_quantity = available_quantity + #{quantity}, " +
            "locked_quantity = locked_quantity - #{quantity}, version = version + 1, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND version = #{version} AND locked_quantity >= #{quantity}")
    int releaseLocked(@Param("skuId") Long skuId, @Param("quantity") int quantity, @Param("version") long version);

    default int releaseLocked(Long skuId, int quantity) { return releaseLocked(skuId, quantity, 0L); }

    @Update("UPDATE product_inventory SET available_quantity = #{availableQuantity}, version = version + 1, updated_at = NOW() " +
            "WHERE sku_id = #{skuId} AND version = #{version}")
    int updateAvailable(@Param("skuId") Long skuId, @Param("availableQuantity") int availableQuantity,
                        @Param("version") long version);
}
