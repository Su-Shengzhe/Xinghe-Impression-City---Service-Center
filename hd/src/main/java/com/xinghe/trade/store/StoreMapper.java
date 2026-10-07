package com.xinghe.trade.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 提供店铺表的基础数据访问操作。 */
@Mapper
public interface StoreMapper extends BaseMapper<Store> {}
