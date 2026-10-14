package com.xinghe.trade.order;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TradeOrderMapperSqlTest {
    @Test
    void expiryScanSqlUsesDeadlineIndexAndKeysetCursor() {
        Configuration configuration = new Configuration();
        configuration.addMapper(TradeOrderMapper.class);
        var statement = configuration.getMappedStatement(TradeOrderMapper.class.getName() + ".selectExpiredOrders");

        String firstPage = statement.getBoundSql(Map.of("limit", 100)).getSql();
        String nextPage = statement.getBoundSql(Map.of(
                "afterExpiry", LocalDateTime.now(), "afterOrderNo", "XH-1", "limit", 100)).getSql();

        assertThat(firstPage).contains("expires_at <= NOW(6)").doesNotContain("expires_at > ?");
        assertThat(nextPage).contains("expires_at > ?").contains("order_no > ?");
    }
}
