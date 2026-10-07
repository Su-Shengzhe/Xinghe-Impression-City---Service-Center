# C 阶段测试计划：最小交易链路

## 1. 测试目标

验证阶段 C 已实现的最小交易闭环：

商品查询 -> 商品管理 -> 库存管理 -> 购物车 -> 多店铺结算 -> 父子订单 -> 订单详情/取消 -> 店铺发货 -> 物流查询。

本阶段重点验证业务正确性、用户/店铺数据隔离、服务端价格与库存校验、订单与库存数据一致性，以及前端联调可用性。

阶段 D 的并发库存、支付幂等专项压力测试不作为 C 阶段通过条件；RabbitMQ、Elasticsearch 也不作为本次基础交易链路的必需依赖。

## 2. 测试范围

### 2.1 纳入范围

- 商品搜索：只返回 `ON_SALE` 商品，支持名称/描述关键词。
- 店铺商品维护：新增、编辑、上下架、库存调整、店铺归属校验。
- 购物车：查询、添加、重复添加累加、改数量、删除、用户隔离。
- 结算：按服务端商品价格计算金额，锁定库存，清空结算快照，生成父订单和按店铺聚合的子订单。
- 订单：详情、父子订单关系、商品价格快照、用户隔离、待支付取消和库存释放。
- 支付/发货前置状态：支付接口作为发货前置条件进行验证；不把真实支付渠道纳入 C 阶段。
- 发货与物流：店铺归属校验、发货状态、重复发货拦截、物流查询。
- 异常返回：参数错误 400、越权 403、状态冲突 409、未预期错误 500。

### 2.2 不纳入范围

- 100 并发库存竞争、超卖率、P95 性能指标。
- 支付幂等 Token 的并发专项和支付网关对接。
- RabbitMQ 延迟消息、重试、死信队列。
- Elasticsearch 索引同步、中文分词和 MySQL 降级搜索。
- 优惠券、地址、退款、售后工单、复杂 RBAC。

## 3. 环境与测试数据

### 3.1 软件环境

- JDK 与 Maven：以 `hd/pom.xml` 要求为准。
- MySQL：创建数据库 `xinghe_trade`。
- Redis：购物车结算幂等和支付幂等依赖 Redis。
- RabbitMQ：C 阶段可关闭，`XINGHE_MESSAGING_ENABLED=false`；若验证超时消费者再单独开启。
- Elasticsearch：C 阶段不要求启动。
- 后端端口：`8081`；前端端口：`5173`。

### 3.2 启动步骤

1. 启动 MySQL 和 Redis，并创建数据库：

   ```sql
   CREATE DATABASE IF NOT EXISTS xinghe_trade DEFAULT CHARACTER SET utf8mb4;
   ```

2. 在项目根目录配置 `.env` 或环境变量：

   ```powershell
   $env:XINGHE_SEED_ENABLED = "true"
   $env:XINGHE_MESSAGING_ENABLED = "false"
   ```

3. 启动后端：

   ```powershell
   cd hd
   mvn -s maven-settings.xml spring-boot:run
   ```

4. 可选启动前端：

   ```powershell
   cd qd
   npm install
   npm run dev
   ```

5. 确认健康基线：访问 `http://localhost:8081` 或调用任一接口，确认没有数据库连接错误；前端访问 `http://localhost:5173`。

### 3.3 基础数据

演示数据开启后应存在：

| 数据 | 值 |
|---|---|
| 用户 A | `user-1001` |
| 用户 B | `user-2002`，可手工使用相同结构的请求验证隔离 |
| 店铺 1 | `X-Store-Id: 1` |
| 演示 SKU | `10001` |
| 演示商品 | 星河精选运动鞋 |
| 演示价格 | `199.00` |
| 演示库存 | `20` |
| 演示订单 | `XH2026100300000001` |
| 演示物流单号 | `XH-DEMO-TRACK-001` |

多店铺结算需要至少准备两个营业店铺和三个在售 SKU：店铺 1 有 SKU-101、SKU-102，店铺 2 有 SKU-201。可以通过商品新增接口准备，示例请求见后文。

## 4. 测试约定

- 成功响应：HTTP 200，JSON `code=0`，`message=success`。
- 参数错误：HTTP 400，`code=-1`。
- 用户或店铺越权：HTTP 403，`code=-1`。
- 订单状态冲突、库存不足、重复发货：HTTP 409，`code=-1`。
- 每次结算使用新的 `Idempotency-Token`，重复结算验证时必须复用同一 Token。
- 测试执行记录至少保存：请求时间、请求体、响应体、订单号、库存变更前后值、数据库核对结果和缺陷编号。

## 5. 详细测试步骤

### TC-C-001 数据库结构与演示数据

**目的**：确认 C 阶段所需表和基础数据可用。

1. 启动后端，观察启动日志无 schema、MySQL、Redis 错误。
2. 执行：

   ```sql
   SHOW TABLES;
   SELECT COUNT(*) FROM store;
   SELECT id, store_id, name, price, status FROM product WHERE id=10001;
   SELECT * FROM product_inventory WHERE sku_id=10001;
   SELECT order_no, user_id, status, amount FROM trade_order WHERE order_no='XH2026100300000001';
   SELECT order_no, company, tracking_no, status FROM logistics_record WHERE order_no='XH2026100300000001';
   ```

3. 检查 `product`、`product_inventory`、`cart_item`、`trade_order`、`trade_order_item`、`logistics_record` 均存在。
4. 预期：演示商品为 `ON_SALE`、价格 `199.00`、库存记录存在；演示订单属于 `user-1001` 且物流记录存在。

### TC-C-002 商品搜索

1. 调用无关键词搜索：

   ```http
   GET http://localhost:8081/api/customer-service/products?keyword=
   ```

2. 调用关键词搜索：

   ```http
   GET http://localhost:8081/api/customer-service/products?keyword=运动鞋
   ```

3. 检查结果只包含 `status=ON_SALE` 的商品，名称或描述包含关键词。
4. 将演示商品下架后重复搜索，确认该商品不再返回；恢复上架后再次确认返回。
5. 发送超过 80 个字符的关键词，预期 HTTP 400，且不会执行正常搜索。

### TC-C-003 新增商品和初始库存

1. 准备一个不存在的 SKU，例如 `10101`。
2. 请求：

   ```http
   POST http://localhost:8081/api/store/products
   X-Store-Id: 1
   Content-Type: application/json

   {
     "skuId": 10101,
     "name": "C阶段测试鞋",
     "description": "商品新增测试",
     "price": 88.50,
     "availableQuantity": 10
   }
   ```

3. 预期 HTTP 200，返回商品状态 `ON_SALE`。
4. 执行数据库核对：

   ```sql
   SELECT store_id, name, price, status FROM product WHERE id=10101;
   SELECT available_quantity, locked_quantity FROM product_inventory WHERE sku_id=10101;
   ```

5. 预期商品属于店铺 1，库存为 `10/0`。
6. 重复使用 SKU-10101 新增，预期 HTTP 400，不产生第二条商品或库存记录。
7. 使用不存在店铺或停业店铺的 `X-Store-Id`，预期 HTTP 400。

### TC-C-004 编辑、上下架和库存调整

1. 编辑 SKU-10101：

   ```http
   PUT http://localhost:8081/api/store/products/10101
   X-Store-Id: 1
   Content-Type: application/json

   {"name":"C阶段测试鞋-改名","description":"已编辑","price":99.90}
   ```

2. 查询商品，预期名称、描述、价格更新，库存仍为 10。
3. 使用错误店铺：`X-Store-Id: 2`，预期 HTTP 400 或 403，商品资料不变。
4. 下架：

   ```http
   PUT /api/store/products/10101/status
   X-Store-Id: 1
   Content-Type: application/json

   {"status":"OFF_SALE"}
   ```

5. 确认商品搜索不返回该商品，购物车新增该 SKU 返回 HTTP 400。
6. 使用非法状态 `INVALID`，预期 HTTP 400。
7. 调整库存：

   ```http
   PUT /api/store/products/10101/inventory
   X-Store-Id: 1
   Content-Type: application/json

   {"availableQuantity":25}
   ```

8. 预期可用库存变为 25；发送 `-1` 预期 HTTP 400，数据库不变。

### TC-C-005 购物车新增、查询、累加、改量和删除

1. 确保 SKU-10001 为在售。
2. 添加 2 件：

   ```http
   POST http://localhost:8081/api/cart/items
   X-User-Id: user-1001
   Content-Type: application/json

   {"skuId":10001,"quantity":2}
   ```

3. 查询购物车：`GET /api/cart`，预期有 SKU-10001，数量 2，单价 199.00，行金额 398.00。
4. 再添加 3 件，预期数量累加为 5，而不是新增第二行。
5. 修改数量：

   ```http
   PUT /api/cart/items/10001
   X-User-Id: user-1001
   Content-Type: application/json

   {"quantity":1}
   ```

6. 预期数量为 1、行金额为 199.00。
7. 删除：`DELETE /api/cart/items/10001`，再查询预期返回空数组。
8. 边界验证：数量 0、100、缺少 `skuId`，预期 HTTP 400。
9. 对不存在的 SKU、下架 SKU 添加，预期 HTTP 400。
10. 对不存在的购物车商品改量或删除，预期 HTTP 400。

### TC-C-006 购物车用户隔离

1. 使用 `user-1001` 添加 SKU-10001 数量 2。
2. 使用 `user-2002` 调用 `GET /api/cart`，预期为空。
3. 使用 `user-2002` 修改或删除 SKU-10001，预期 HTTP 400，不能影响用户 A。
4. 再使用 `user-1001` 查询，预期数量仍为 2。
5. 检查数据库：

   ```sql
   SELECT user_id, sku_id, quantity FROM cart_item WHERE sku_id=10001;
   ```

### TC-C-007 多店铺结算生成父子订单

**前置**：准备店铺 1 的 SKU-101、SKU-102，店铺 2 的 SKU-201，均为在售且库存充足。

1. 使用 `user-1001` 分别添加：SKU-101 数量 1、SKU-102 数量 2、SKU-201 数量 3。
2. 记录结算前库存：

   ```sql
   SELECT sku_id, available_quantity, locked_quantity
   FROM product_inventory WHERE sku_id IN (101,102,201);
   ```

3. 调用结算：

   ```http
   POST http://localhost:8081/api/orders/checkout
   X-User-Id: user-1001
   Idempotency-Token: checkout-c-001
   ```

4. 预期 HTTP 200，返回 3 条结果：1 个父订单、2 个店铺子订单，初始状态均为 `PENDING_PAYMENT`。
5. 查询父订单：

   ```http
   GET http://localhost:8081/api/orders/{parentOrderNo}
   X-User-Id: user-1001
   ```

6. 预期父订单 `skuId`、`quantity` 为空，金额等于三个商品行金额之和；子订单按店铺各 1 条，同店 SKU 聚合在同一子订单。
7. 核验订单明细：每条明细记录 `skuId`、数量、`unitPrice`、`lineAmount`，价格为结算时服务端商品价格。
8. 查询购物车，预期本次结算快照已清空。
9. 核验库存：每个 SKU 的 `available_quantity` 减少购买数量，`locked_quantity` 增加购买数量。
10. 校验客户端不能传价格：结算接口无金额字段；若使用旧的单笔订单接口 `/api/orders` 传入错误金额，预期 HTTP 400 且库存不变。

### TC-C-008 结算幂等和失败回滚

1. 先在购物车放入一件商品。
2. 用相同用户和 Token `checkout-c-002` 连续调用结算两次。
3. 预期两次响应返回同一个父订单号，不产生第二个父订单，不重复锁库存。
4. 用不同 Token 对已清空购物车再次结算，预期 HTTP 409，提示购物车为空。
5. 将库存调整为不足购买量，结算预期 HTTP 409。
6. 结算失败后核对购物车仍存在，订单表无本次半成品订单，库存锁定量未增加。

### TC-C-009 订单详情和用户越权

1. 使用订单所属用户查询父订单和子订单详情，预期 HTTP 200。
2. 使用 `user-2002` 查询同一订单，预期 HTTP 403。
3. 使用不存在订单号查询，预期 HTTP 400。
4. 检查响应中父订单包含子订单列表，子订单明细包含服务端价格快照；不出现其他用户订单。

### TC-C-010 取消待支付父订单并释放库存

1. 创建一笔新的多店铺待支付父订单，记录各 SKU 的 `available_quantity` 和 `locked_quantity`。
2. 调用：

   ```http
   POST http://localhost:8081/api/orders/{parentOrderNo}/cancel
   X-User-Id: user-1001
   ```

3. 重新查询父订单和子订单，预期子订单为 `CANCELLED`，父订单同步为取消终态。
4. 核验库存：对应 SKU 的锁定量全部释放，可用量恢复到结算前值。
5. 再次取消同一订单，预期 HTTP 409，不重复释放库存。
6. 对已支付或已发货订单取消，预期 HTTP 409。
7. 使用其他用户取消，预期 HTTP 403，订单和库存均不变。

### TC-C-011 支付状态与店铺发货

1. 创建新的购物车结算订单，获取父订单号和子订单号。
2. 未支付直接调用子订单发货：

   ```http
   POST http://localhost:8081/api/orders/{childOrderNo}/ship
   X-Store-Id: 1
   Content-Type: application/json

   {"company":"测试物流","trackingNo":"TEST-001"}
   ```

3. 预期 HTTP 409，提示仅已支付订单可以发货，不能写入物流。
4. 使用订单所属用户和新的 `Idempotency-Token` 支付父订单：

   ```http
   POST http://localhost:8081/api/orders/{parentOrderNo}/pay
   X-User-Id: user-1001
   Idempotency-Token: pay-c-001
   ```

5. 预期父订单和所有子订单变为 `PAID`，锁定库存转为已售库存。
6. 使用错误用户支付，预期 HTTP 403；确认 Redis 幂等 Token 未被错误用户占用。
7. 已支付父订单重复支付，预期返回已支付结果，不产生额外库存变化。
8. 使用正确店铺发货，预期 HTTP 200，物流状态 `SHIPPED`，子订单状态 `SHIPPED`。
9. 使用错误 `X-Store-Id` 发货，预期 HTTP 403，不写入物流。
10. 再次对同一子订单发货，预期 HTTP 409。
11. 空承运商或空运单号，预期 HTTP 400。

### TC-C-012 物流查询与归属校验

1. 使用已发货子订单调用：

   ```http
   GET http://localhost:8081/api/customer-service/orders/{childOrderNo}/logistics
   X-User-Id: user-1001
   ```

2. 预期返回承运商、运单号、`SHIPPED` 状态和描述。
3. 对已支付但未发货订单查询，预期返回 `NOT_SYNCED`，不应视为已发货。
4. 使用其他用户查询，预期 HTTP 403。
5. 查询演示订单 `XH2026100300000001`，预期返回 `星河速运`、`XH-DEMO-TRACK-001` 和 `IN_TRANSIT`。

### TC-C-013 前端联调主流程

1. 浏览器打开 `http://localhost:5173`。
2. 以 `user-1001` 进入商品搜索，搜索“鞋”，确认返回在售商品。
3. 将商品加入购物车，打开购物车确认数量和金额。
4. 完成结算，记录父订单号。
5. 打开订单详情，确认父子订单、金额、商品明细正确。
6. 打开客服工作台，查询订单号，确认客服接口返回订单信息。
7. 查询物流，确认物流状态和运单号展示。
8. 切换为 `user-2002` 或修改请求头后，确认看不到用户 A 的购物车、订单和物流。
9. 检查失败场景页面：下架商品、库存不足、无权限、重复发货均显示错误而不是白屏。

## 6. 自动化测试与执行顺序

### 6.1 单元测试

先执行已有测试：

```powershell
cd hd
mvn -s maven-settings.xml test
```

验收时必须看到实际测试数量大于 0，并确认 `Failures=0`、`Errors=0`。当前环境曾出现 Surefire 报告目录拒绝写入并显示 `Tests run: 0` 的情况；若复现，应先修复 `hd/target/surefire-reports` 权限或清理构建目录，再重新执行，不能将该结果视为通过。

建议补充不依赖人工 UI 的接口自动化，至少覆盖：购物车用户隔离、结算父子订单、服务端价格、库存不足回滚、取消释放库存、店铺发货归属和物流归属。

### 6.2 手工接口测试

按 TC-C-001 至 TC-C-012 顺序执行。TC-C-007 产生的数据供 TC-C-009 至 TC-C-012 使用；每轮结束后清理临时 SKU、购物车和未完成订单，保留一组成功演示数据。

### 6.3 前端回归

后端接口测试通过后执行 TC-C-013，避免把前端显示问题与后端业务问题混在一起。

## 7. 数据库核对清单

每次关键操作后执行以下核对：

```sql
-- 商品与库存
SELECT p.id, p.store_id, p.status, p.price,
       i.available_quantity, i.locked_quantity
FROM product p LEFT JOIN product_inventory i ON i.sku_id=p.id
WHERE p.id IN (10001,10101,101,102,201);

-- 当前用户购物车
SELECT user_id, sku_id, quantity FROM cart_item
WHERE user_id IN ('user-1001','user-2002') ORDER BY user_id, sku_id;

-- 父子订单
SELECT order_no, parent_order_no, user_id, store_id, sku_id,
       quantity, amount, status
FROM trade_order WHERE user_id='user-1001'
ORDER BY created_at DESC;

-- 订单价格快照
SELECT order_no, sku_id, quantity, unit_price, line_amount
FROM trade_order_item WHERE order_no IN ('<parent-or-child-order-no>');

-- 物流唯一性
SELECT order_no, COUNT(*) AS logistics_count
FROM logistics_record GROUP BY order_no HAVING COUNT(*) > 1;
```

核心不变量：

- 购物车同一用户同一 SKU 最多一行。
- 父订单不绑定单一 SKU；子订单必须带父订单号和店铺。
- 父订单金额 = 所有子订单金额之和；子订单金额 = 明细行金额之和。
- 订单明细单价是下单时快照，不随商品后续改价变化。
- 结算成功后购物车快照删除；结算失败不删除。
- 待支付取消只释放对应明细的锁定库存，不能释放其他订单库存。
- 每个子订单最多一条物流记录。

## 8. 通过标准

满足以下条件才判定 C 阶段通过：

1. TC-C-001 至 TC-C-013 的主流程用例全部通过，无 P0/P1 缺陷。
2. 商品、购物车、订单、物流接口的用户/店铺隔离全部通过。
3. 多店铺结算能稳定生成 1 个父订单和按店铺聚合的子订单。
4. 服务端价格、库存锁定、取消释放、订单明细快照核对一致。
5. 发货必须经过支付和店铺归属校验，物流查询结果与订单状态一致。
6. 自动化测试真实执行且 `Failures=0`、`Errors=0`；不能接受 `Tests run: 0`。
7. 前端主流程可完成搜索、加购、结算、订单查询和物流查询。

## 9. 测试输出物

- 测试执行记录表：用例编号、执行人、日期、结果、证据链接。
- 接口请求/响应 JSON 或 Postman/Apifox 导出。
- 关键操作前后数据库查询结果。
- 自动化测试完整日志。
- 缺陷清单：严重级别、复现步骤、实际结果、预期结果、修复版本、回归结果。
- 一条可复现的演示链路：搜索商品 -> 加购 -> 多店铺结算 -> 查询父子订单 -> 支付 -> 发货 -> 物流查询。

## 10. 后续 D 阶段入口

C 阶段通过后再开展：100 请求竞争有限库存、20 个并发支付请求复用同一 Token、支付与超时取消竞态、库存最终值和 P95 统计。D 阶段结果不得回填为 C 阶段已验证结论。
