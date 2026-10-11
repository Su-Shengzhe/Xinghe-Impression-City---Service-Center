# 阶段 F 修复专项复测结果

## 1. 结论

**专项修复验收：通过。**

FIX-01、FIX-02、FIX-03 的重点自动化测试、真实 HTTP 验证和数据库证据相互一致；没有发现未解释的 5xx、跨店数据泄露、库存被资料更新覆盖或物流结果错配。

本结论针对当前工作区被测状态，不代表未提交改动被合并前的原始 `HEAD`。本轮没有修改业务代码。

## 2. 执行基线

| 项目 | 实测值 |
|---|---|
| Commit | `c759c381e27fa40712d5ba4fc9b4f923d25bbaa0` |
| PowerShell | 7.6.6 |
| 测试库 | `xinghe_stage_f_fix` |
| 运行时后端 | `http://localhost:8082`；8081 原有实例未复用 |
| Redis | localhost:6379，运行正常 |
| RabbitMQ | 未监听；本轮 `XINGHE_MESSAGING_ENABLED=false` |
| Elasticsearch | 未监听；本轮 `XINGHE_ELASTICSEARCH_ENABLED=false` |
| Seed | `XINGHE_SEED_ENABLED=false` |
| 结果目录 | `stage-f-results\after-fix-revalidation` |
| 历史证据 | 未覆盖 `stage-f-results\after-fix` 和 `final-report` |

工作区在测试前已有未提交业务、测试、文档和历史证据改动；本轮只新增本目录证据，没有将工作区恢复或清理。

## 3. 自动化与构建

| 项目 | 结果 | 证据 |
|---|---|---|
| 重点测试 | 15/15，Failures=0，Errors=0，BUILD SUCCESS | `01-focused-backend-test.log` |
| 后端完整测试 | 51/51，Failures=0，Errors=0，BUILD SUCCESS | `02-full-backend-test.log` |
| 前端生产构建 | Vite build 成功，1560 modules transformed | `03-frontend-build.log` |
| Flyway | V1/V2/V3 success=1 | `12-database-evidence.sql.txt` |
| 证据 JSON | 全部可解析 | 执行后 JSON validation |
| `git diff --check` | 通过（仅有换行符提示） | 最终检查命令 |

## 4. 专项结果矩阵

| 用例/修复点 | 实测结果 | 数据库/调用证据 | 结论 |
|---|---|---|---|
| FIX-01 / F-FIX-API-01 | `GET /api/customer-service/products/0`、`/-1` 均 HTTP 400，统一 `请求参数无效`；正常 SKU HTTP 200 | `CustomerServiceControllerTest` 通过，并验证 `ProductQueryService` 无交互；`04-detail-invalid-0.json`、`05-detail-invalid-negative.json`、`06-detail-valid.json` | 通过 |
| F-FIX-API-02 | 不存在 SKU HTTP 400，响应 `商品不存在或已下架`，无 5xx；下架 SKU HTTP 400，恢复上架后 HTTP 200 | `06b-detail-not-found.json`、`17-detail-off-sale.json`、`19-detail-restored-on-sale.json` | 通过 |
| FIX-02 / F-FIX-API-03 | 资料更新 HTTP 200，只改变名称、描述、价格 | 更新前库存 `available=8, locked=2, sold=0, version=1`；更新后相同，见 `07-product-before.sql.txt`、`09-product-after.sql.txt` | 通过 |
| F-FIX-API-04 | 请求额外发送 `skuId`、`availableQuantity`、`lockedQuantity`、`soldQuantity`；HTTP 200 | 库存未被额外字段覆盖；`08b-product-update-extra-fields.json`、`09-product-after.sql.txt` | 通过 |
| F-FIX-API-05 | 专用库存接口 HTTP 200，将可用库存从 8 改为 7 | `locked=2`、`sold=0` 保持不变，`version` 由 1 到 2；`09b-inventory-update.json`、`09c-product-after-inventory-api.sql.txt` | 通过 |
| F-FIX-API-08 | 店铺 2 更新店铺 1 SKU HTTP 400，提示 `店铺商品不存在` | `08c-product-update-cross-store.json`；无资料/库存变化 | 通过 |
| FIX-03 / F-FIX-UNIT-05 | 两个订单的物流返回顺序与订单顺序一致 | `OrderServiceTest` 断言 `selectList(IN ...)` 调用 1 次、`selectOne` 0 次；15/15 重点测试通过 | 通过 |
| F-FIX-UNIT-06 / API-07 | 无子订单店铺查询 HTTP 200，`data=[]` | `11-store-orders-empty.json`；单元测试验证空列表不与物流 mapper 交互 | 通过 |
| F-FIX-API-06 | 店铺 1 返回 2 个子订单：最新订单物流为 `null`，已发货订单返回正确公司/运单/状态 | `10-store-orders-batch.json`、`10-store-order-validation.json`；物流 SQL 仅挂在已发货子订单 | 通过 |
| F-FIX-API-08 | 店铺 2 不能发货店铺 1 订单，HTTP 403；店铺列表只返回自身商品 | `11b-cross-store-ship.json`、`20-store-products-owned.json`、`21-store-products-cross-store-list.json` | 通过 |

## 5. 真实交易与数据库证据

### 5.1 订单和库存

- 第一笔父订单 `XH17916377907788A1892` 状态为 `SHIPPED`，子订单 `XH17916377907825F0A08` 属于店铺 1。
- 第二笔父订单 `XH1791638110182D52A1F` 状态为 `PAID`，子订单 `XH17916381101855415EA` 属于店铺 1，尚无物流。
- SKU `990020` 最终为 `available=6, locked=0, sold=3, version=5`；两笔订单数量为 2 和 1，库存售出数量与订单一致。
- SKU `990021` 最终为 `available=10, locked=0, sold=0, version=0`。
- 两条支付幂等记录均为 `SUCCEEDED`、`result_status=PAID`，没有重复成功支付记录。

### 5.2 物流批量映射

- `XH17916377907825F0A08` 对应 `Stage F Fix Carrier / SF-FIX-TRACK-1 / SHIPPED`。
- `XH17916381101855415EA` 没有物流记录，店铺订单响应中的 `logistics` 为 `null`；用户物流接口返回 `NOT_SYNCED`，不是 500。
- `GET /api/orders/store` 返回顺序为第二笔子订单、第一笔子订单，与 `selectStoreOrders` 的创建时间倒序一致；物流没有错挂。

完整原始 SQL 输出见 `12-database-evidence.sql.txt`。

## 6. P1 回归

- 同 Token 重复支付：HTTP 200 幂等重放。
- 不同 Token 重复支付：HTTP 409，`当前订单不可支付`。
- 重复发货：HTTP 409，`仅已支付订单可以发货`。
- 已支付订单取消：HTTP 409，`当前订单不可取消`。
- 商品下架详情：HTTP 400；恢复上架详情：HTTP 200。
- 客服订单/物流所属用户查询：HTTP 200；其他用户查询：HTTP 403。
- 商品搜索：HTTP 200，返回在售测试商品。

原始响应见 `12-pay-repeat-same-token.json` 至 `21-store-products-cross-store-list.json`。

## 7. 偏差、限制与遗留

1. 计划表中的跨店发货示例写作 HTTP 400；当前统一 `SecurityException` 契约实际返回 HTTP 403（`11b-cross-store-ship.json`）。这是正确的权限拒绝语义，且数据库无状态变化，不构成缺陷；报告按“拒绝成功”判定。
2. 计划的批量查询单元测试可以证明一次 `selectList(IN ...)` 和零次逐单 `selectOne`，真实 HTTP/SQL 证据证明结果顺序和映射正确，但未开启 SQL 日志，因此没有用数据库日志计算真实 SQL 次数。
3. 本轮使用 8082 是因为 8081 已有无法可靠确认数据库归属的实例；专用实例日志明确记录 JDBC 数据库为 `xinghe_stage_f_fix`。8082 临时实例在收尾时停止，未影响 8081。
4. `message_outbox` 中有 6 条 `PRODUCT_INDEX/PENDING` 记录，这是本轮消息功能关闭、Elasticsearch 关闭时商品写入产生的预期待处理事件；不属于本轮三个修复点，未清理。
5. 本轮未重新执行完整阶段 F 客服 SSE 页面人工验收；本修复专项已执行商品搜索、客服订单/物流 HTTP 权限和物流未同步分支。评论、客服运营端等计划明确排除项不纳入本轮。

## 8. 证据清单

主要文件：`00-baseline.txt`、`01-focused-backend-test.log`、`02-full-backend-test.log`、`03-frontend-build.log`、`04-detail-invalid-0.json`、`05-detail-invalid-negative.json`、`06-detail-valid.json`、`07-product-before.sql.txt`、`08-product-update.json`、`09-product-after.sql.txt`、`10-store-orders-batch.json`、`11-store-orders-empty.json`、`12-database-evidence.sql.txt`、`defect-list.md`。