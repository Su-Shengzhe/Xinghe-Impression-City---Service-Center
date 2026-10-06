package com.xinghe.trade.cart;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
    @Insert("INSERT INTO cart_item (user_id, sku_id, quantity, created_at, updated_at) " +
            "VALUES (#{userId}, #{skuId}, #{quantity}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE quantity = cart_item.quantity + VALUES(quantity), updated_at = NOW()")
    int addQuantity(@Param("userId") String userId,
                    @Param("skuId") Long skuId,
                    @Param("quantity") int quantity);
}
