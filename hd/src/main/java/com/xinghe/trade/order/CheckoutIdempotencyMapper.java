package com.xinghe.trade.order;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CheckoutIdempotencyMapper {
    @Insert("INSERT IGNORE INTO checkout_idempotency " +
            "(user_id, token, status, created_at, updated_at) " +
            "VALUES (#{userId}, #{token}, 'PROCESSING', NOW(6), NOW(6))")
    int insertProcessing(@Param("userId") String userId, @Param("token") String token);

    @Select("SELECT user_id, token, parent_order_no, status FROM checkout_idempotency " +
            "WHERE user_id = #{userId} AND token = #{token} FOR UPDATE")
    CheckoutIdempotency selectForUpdate(@Param("userId") String userId, @Param("token") String token);

    @Update("UPDATE checkout_idempotency SET parent_order_no = #{parentOrderNo}, " +
            "status = 'SUCCEEDED', updated_at = NOW(6) " +
            "WHERE user_id = #{userId} AND token = #{token} AND status = 'PROCESSING'")
    int markSucceeded(@Param("userId") String userId, @Param("token") String token,
                      @Param("parentOrderNo") String parentOrderNo);
}
