package com.xinghe.trade.payment;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 支付 Token 的数据库唯一约束和行锁访问。 */
@Mapper
public interface PaymentIdempotencyMapper extends BaseMapper<PaymentIdempotency> {
    @Insert("INSERT IGNORE INTO payment_idempotency " +
            "(token, order_no, user_id, status, created_at, updated_at) " +
            "VALUES (#{record.token}, #{record.orderNo}, #{record.userId}, 'PROCESSING', NOW(), NOW())")
    int insertProcessing(@Param("record") PaymentIdempotency record);

    @Select("SELECT * FROM payment_idempotency WHERE token = #{token} FOR UPDATE")
    PaymentIdempotency selectForUpdate(@Param("token") String token);

    @Select("SELECT * FROM payment_idempotency WHERE order_no = #{orderNo} " +
            "AND status = 'SUCCEEDED' ORDER BY updated_at LIMIT 1 FOR UPDATE")
    PaymentIdempotency selectSucceededByOrderNo(@Param("orderNo") String orderNo);
}