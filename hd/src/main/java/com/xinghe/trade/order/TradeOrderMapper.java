package com.xinghe.trade.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 提供父订单和店铺子订单的基础读写操作。 */
@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {
}
