package com.xinghe.trade.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDateTime;
import java.util.List;

/** 提供父订单和店铺子订单的基础读写操作。 */
@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {
    @Select("SELECT * FROM trade_order WHERE order_no = #{orderNo} FOR UPDATE")
    TradeOrder selectForUpdate(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM trade_order WHERE store_id = #{storeId} AND parent_order_no IS NOT NULL " +
            "ORDER BY created_at DESC, order_no DESC LIMIT #{offset}, #{limit}")
    List<TradeOrder> selectStoreOrders(@Param("storeId") Long storeId,
                                      @Param("offset") int offset, @Param("limit") int limit);

    @Select({"<script>",
            "SELECT order_no, expires_at FROM trade_order WHERE status = 'PENDING_PAYMENT'",
            "AND expires_at &lt;= NOW(6) AND (parent_order_no IS NOT NULL OR sku_id IS NOT NULL)",
            "<if test='afterExpiry != null'>",
            "AND (expires_at &gt; #{afterExpiry} OR (expires_at = #{afterExpiry} AND order_no &gt; #{afterOrderNo}))",
            "</if>",
            "ORDER BY expires_at, order_no LIMIT #{limit}",
            "</script>"})
    List<TradeOrder> selectExpiredOrders(@Param("afterExpiry") LocalDateTime afterExpiry,
                                         @Param("afterOrderNo") String afterOrderNo,
                                         @Param("limit") int limit);
}
