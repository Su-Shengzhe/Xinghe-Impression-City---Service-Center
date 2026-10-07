package com.xinghe.trade.logistics;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 提供物流记录的基础数据访问操作。 */
@Mapper
public interface LogisticsMapper extends BaseMapper<LogisticsRecord> {
}
