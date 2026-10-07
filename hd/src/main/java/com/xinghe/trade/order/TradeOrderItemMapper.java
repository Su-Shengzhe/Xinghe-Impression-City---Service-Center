package com.xinghe.trade.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 提供订单商品价格快照明细的基础读写操作。 */
@Mapper
public interface TradeOrderItemMapper extends BaseMapper<TradeOrderItem> {
}
