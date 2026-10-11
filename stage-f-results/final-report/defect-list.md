# 阶段 F 缺陷清单

| 编号 | 级别 | 问题 | 处理 | 证据/状态 |
|---|---|---|---|---|
| F-P1-01 | P1 | 商品库存负数请求因 `HandlerMethodValidationException` 未被统一处理而返回 500 | 已修复：新增 400 异常映射，并完成修复后 HTTP 复测 | `after-fix/05-inventory-negative-after-fix.json`，已关闭 |
| F-TEST-01 | 测试口径 | 空购物车结算计划写作 400，实际统一业务冲突返回 409 | 已按实际接口契约修正脚本与报告 | `p1/44-empty-cart-checkout.json` |
| F-TEST-02 | 测试口径 | 首次客服物流查询使用父订单号，而物流记录挂在子订单号 | 已使用子订单号复测并确认 SSE 正常 | `after-fix/50-sse-child-logistics-after-fix.json`，已关闭 |

当前没有 Blocker 或未解释的 P0 缺陷。