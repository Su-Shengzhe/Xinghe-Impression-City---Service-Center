package com.xinghe.trade.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 提供父订单和店铺子订单的基础读写操作。 */
@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {
    @Select("SELECT * FROM trade_order WHERE order_no = #{orderNo} FOR UPDATE")
    TradeOrder selectForUpdate(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM trade_order WHERE store_id = #{storeId} AND parent_order_no IS NOT NULL ORDER BY created_at DESC LIMIT 100")
    List<TradeOrder> selectStoreOrders(@Param("storeId") Long storeId);
}
