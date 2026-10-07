package com.xinghe.trade.product;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 商品表基础数据访问接口，复杂管理规则由商品服务负责。 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
