# 阶段 F 联调验收总结

## 结论

**有条件通过。** F1 用户交易、F2 店铺履约、F3 用户客服主线以及用户/店铺越权检查均通过；修复后的库存负数边界返回正确的 `400`。当前没有未解释的 Blocker 或 P0 缺陷。

本结论基于以下被测版本和工作区状态：

- 被测 commit：`c759c381e27fa40712d5ba4fc9b4f923d25bbaa0`
- 测试库：`xinghe_stage_f`
- 后端：`http://localhost:8081`
- 结果目录：`stage-f-results/`
- 工作区：包含阶段 F 开始前已有的未提交业务改动；这些改动属于本次验收范围，结果不代表默认分支原始版本。

## 构建与测试

| 项目 | 结果 | 证据 |
|---|---|---|
| 前端生产构建 | 通过，Vite 1560 modules transformed | `baseline/frontend-build-live.log` |
| 后端单元测试 | 通过，47 tests, 0 failures, 0 errors | `baseline/backend-test-after-fix.log` |
| Flyway | V1、V2、V3 均 success=1 | `final-report/database-evidence-after-fix.txt` |
| 修复后应用启动 | 通过，端口 8081 | `runtime/backend-stage-f-after-fix.log` |

## 主线结论

| 范围 | 实测结论 | 证据 |
|---|---|---|
| F1 交易 | 搜索、详情、购物车、结算、父订单支付、订单详情通过；支付子订单返回 409 | `p0-trade/01-search-live.json`、`p0-trade/07-checkout-live.json`、`p0-trade/09-pay-child-rejected-live.json`、`p0-trade/10-pay-parent-live.json` |
| F2 商品与库存 | 商品创建、资料更新、上下架、库存更新通过；跨店铺写入被拒绝 | `p0-store/01-product-create.json`、`p0-store/03-product-update.json`、`after-fix/04-inventory-update-after-fix.json`、`p0-store/11-product-update-other-store.json` |
| F2 履约 | 店铺只能看到自己的订单；错误店铺发货返回 403；所属店铺发货成功；重复发货返回 409 | `p0-store/17-store-orders-owned.json`、`p0-store/18-store-orders-other-store.json`、`p0-store/21-ship-wrong-store.json`、`p0-store/22-ship-owned.json`、`p0-store/23-ship-repeat.json` |
| F3 客服 | 会话、SSE、历史消息、客服订单/物流/商品工具通过；跨用户访问返回 403 | `p0-customer-service/30-session-create.json`、`p0-customer-service/34-message-history.json`、`p0-customer-service/27-customer-order-other-user.json`、`after-fix/50-sse-child-logistics-after-fix.json` |
| 取消与幂等 | 待支付订单取消成功，重复取消和取消后支付均返回 409；同 Token 支付重放 200，不同 Token 重复支付 409 | `p1/47-cancel-pending-order.json`、`p1/48-cancel-repeat.json`、`p1/49-pay-cancelled-order.json`、`p1/38-pay-repeat-same-token.json`、`p1/39-pay-repeat-different-token.json` |

## 数据库证据

验收主链路在 `xinghe_stage_f` 内形成了正确的父子订单和库存状态：

- 父订单 `XH1791626726063AB4FEA` 与子订单 `XH1791626726071B5207F` 均为 `SHIPPED`，父子关系正确。
- `990010` 库存为 `available=7, locked=0, sold=1`；零库存 SKU `990011` 为 `available=0, locked=0, sold=0`。
- 支付幂等表针对主订单只有一条 `SUCCEEDED` / `PAID` 记录。
- 物流记录只挂在子订单，状态为 `SHIPPED`，承运公司 `StageCarrier`，运单号 `SF-OK-20261010`。
- 取消链路父子订单均为 `CANCELLED`，库存锁定已释放。

完整只读 SQL 输出见 [`database-evidence-after-fix.txt`](database-evidence-after-fix.txt)。

## 已处理问题与口径说明

1. 修复前负数库存更新触发 `HandlerMethodValidationException`，被通用异常分支错误映射为 `500`。当前版本在 `CustomerServiceExceptionHandler` 增加该异常的 `400` 映射；修复后原始响应为 `{"code":-1,"message":"请求参数无效","data":null}`，见 `after-fix/05-inventory-negative-after-fix.json`。
2. 空购物车结算实际返回 `409` 和“购物车为空”，这是当前统一业务冲突契约，不是系统错误；验收脚本预期已同步修正。
3. 物流记录属于店铺子订单。首次用父订单查询得到“物流信息尚未同步”是测试输入与数据模型不匹配；改用子订单复测后 SSE 返回 `SHIPPED`、承运公司和运单号，见 `after-fix/50-sse-child-logistics-after-fix.json`。

## 遗留与限制

- 评论提交/管理、评论 AI、管理端客服运营会话不在本阶段主线，按计划排除。
- 本次阶段结果目录为本地验收证据，不应提交到业务代码版本。
- 前端视觉回归、移动端截图和服务不可用时的完整 UI 状态未形成独立截图证据，属于体验验收补充项，不影响已验证的后端 P0 交易主线。