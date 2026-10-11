package com.xinghe.trade.cart;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 提供购物车记录的数据访问及并发安全的数量累加操作。 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
    /** 在同一事务中锁定当前购物车快照，避免结算期间改量和删除互相覆盖。 */
    @Select("SELECT id, user_id, sku_id, quantity, created_at, updated_at FROM cart_item " +
            "WHERE user_id = #{userId} ORDER BY sku_id FOR UPDATE")
    List<CartItem> selectForCheckout(@Param("userId") String userId);

    /** 仅删除结算开始时锁定的购物车记录，保留结算期间新加入的商品。 */
    @Delete({"<script>",
            "DELETE FROM cart_item WHERE user_id = #{userId} AND id IN",
            "<foreach collection='itemIds' item='itemId' open='(' separator=',' close=')'>",
            "#{itemId}",
            "</foreach>",
            "</script>"})
    int deleteSnapshot(@Param("userId") String userId, @Param("itemIds") List<Long> itemIds);

    /** 插入购物车项；用户与 SKU 冲突时在数据库中原子累加数量。 */
    @Insert("INSERT INTO cart_item (user_id, sku_id, quantity, created_at, updated_at) " +
            "VALUES (#{userId}, #{skuId}, #{quantity}, NOW(), NOW()) " +
            "ON DUPLICATE KEY UPDATE quantity = cart_item.quantity + VALUES(quantity), updated_at = NOW()")
    int addQuantity(@Param("userId") String userId,
                    @Param("skuId") Long skuId,
                    @Param("quantity") int quantity);

}
