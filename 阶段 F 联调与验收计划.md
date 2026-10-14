# 阶段 F 联调与验收计划

## 1. 目标与原则

本阶段目标是验证现有前端与后端能否完成核心交易、店铺履约和用户客服闭环，不重新开发已有功能。阶段范围分为 F1 用户交易、F2 店铺履约、F3 客服联调；评论提交/管理、客服运营端会话管理和评论 AI 不纳入本阶段主线。

执行原则：

- 先固定基线，再进行修改和验收。
- 先验证 P0 主流程，再处理 P1 功能。
- 构建通过不等于功能通过，所有关键结论必须有运行时和数据库证据。
- 测试期间不得向 `xinghe_trade` 或其他非本用例库写入数据。
- 如必须修改业务代码，先提交并记录新的 commit SHA，再继续测试。
- 工作区已有未提交改动时，先明确它们是否纳入被测基线；不能将工作区结果描述成当前 HEAD 的结果。

## 2. 基线冻结

验收开始前记录：

```powershell
$root = "D:\codex-project\Xinghe Impression City Service Center"

git -C $root rev-parse HEAD
git -C $root status --short
git -C $root diff --stat
git -C $root diff
```

基线记录至少包括：

| 项目 | 内容 |
|---|---|
| 基线 commit | 当前 HEAD SHA |
| 工作区状态 | 未提交文件列表 |
| 前端改动 | 文件、功能、是否纳入本阶段 |
| 后端改动 | 文件、功能、是否纳入本阶段 |
| 数据库 | 专用测试库名称 |
| 配置 | 前后端地址、Redis、RabbitMQ、环境变量；不得记录密码 |
| 排除项 | 明确不属于本阶段的改动 |

如果当前 9 个未提交文件全部纳入阶段 F，应在报告中明确写为：

> 阶段 F 基线包含工作区现有未提交改动；验收结果不代表默认分支原始版本的结果。

## 3. 验收范围

### F1：用户交易闭环

- 商品搜索和商品详情。
- 购物车查询、添加、修改数量和删除。
- 购物车结算，验证父订单和店铺子订单关系。
- 订单详情、模拟支付和取消允许取消的订单。

### F2：店铺履约闭环

- 店铺商品创建、资料维护、上下架和库存维护。
- 店铺读取所属子订单。
- 录入承运商与运单号并发货。
- 用户侧查询订单物流。

### F3：客服联调与页面验收

- 创建客服会话，发送消息并接收 SSE 回复。
- 切换会话、刷新后恢复本地会话摘要并从服务端恢复历史消息。
- 使用客服工具查询当前用户的订单、物流和在售商品。
- 验证跨用户订单、物流和会话访问被拒绝。
- 覆盖加载中、空数据、接口错误和客服服务不可用状态。

### P0：必须通过

P0 是阶段 F 的交付主线：

1. 搜索在售商品。
2. 加入购物车。
3. 修改和删除购物车项。
4. 结算生成父订单和店铺子订单。
5. 支付父订单。
6. 取消允许取消的订单。
7. 店铺查看自己的子订单。
8. 店铺录入物流并发货。
9. 用户查询订单详情和物流。
10. 用户创建客服会话、发送消息、接收 SSE 回复、刷新后恢复历史。
11. 验证用户和店铺之间不能越权读取或修改数据。

P0 任一核心步骤出现 5xx、数据错乱、越权或库存/订单状态错误，则主线验收不通过。

### P1：应完成或明确结论

- 商品详情页及详情接口。
- 用户订单历史列表。
- 商品上下架。
- 库存更新及库存边界。
- 重复点击、重复支付、重复发货。
- 空结果、加载失败、网络异常、移动端布局。
- 无物流记录、非法订单状态、非法店铺归属等异常分支。

P1 未完成时，不阻塞 P0，但必须在报告中标记为“未完成”“不纳入本阶段”或“存在缺陷”，不能留作模糊状态。

### 明确遗留项

以下功能不作为 P0 阻塞项：

- 评论提交/评论管理；除非产品方另行明确将其纳入本阶段。
- 评论 AI、情感分析和评论重新分析。
- 管理端客服会话列表、接管、运营回复和状态管理。

现有用户侧客服 UI 不能替代管理端客服会话管理验收。用户会话摘要在本地恢复，也不代表服务端提供运营端会话管理能力。

## 4. 接口核对

为每个功能建立接口核对表。下表是检查起点，实际请求头、字段、响应结构和权限必须以当前 Controller 与前端请求实现为准；不得把预期 HTTP 状态码未经验证直接当成事实。

| 功能 | 方法与路由 | 请求头 | 请求体/参数 | 关键响应 | 权限/异常核对 |
|---|---|---|---|---|---|
| 商品搜索 | `GET /api/customer-service/products` | 无（公开） | `keyword` | 在售商品列表 | 搜索空结果、参数编码、错误响应 |
| 商品详情 | `GET /api/customer-service/products/{skuId}` | 无 | 路径参数 `skuId` | 商品详情 | 是否被前端使用；不存在商品的响应 |
| 购物车查询 | `GET /api/cart` | Session Cookie（CUSTOMER） | — | 购物车行列表 | 用户隔离、空购物车 |
| 加购物车 | `POST /api/cart/items` | Session Cookie + CSRF（CUSTOMER） | `skuId`, `quantity` | 购物车行 | 非法数量、下架/不存在商品 |
| 修改购物车 | `PUT /api/cart/items/{skuId}` | Session Cookie + CSRF（CUSTOMER） | `quantity` | 更新后的购物车行 | 数量边界、商品/用户归属 |
| 删除购物车项 | `DELETE /api/cart/items/{skuId}` | Session Cookie + CSRF（CUSTOMER） | 路径参数 `skuId` | 成功响应 | 用户隔离、重复删除 |
| 结算 | `POST /api/orders/checkout` | Session Cookie + CSRF（CUSTOMER）, `Idempotency-Token` | — | 父子订单结果列表 | 库存不足、空购物车、幂等重放 |
| 支付 | `POST /api/orders/{orderNo}/pay` | Session Cookie + CSRF（CUSTOMER）, `Idempotency-Token` | 路径必须传父订单号 | 支付结果 | 子订单支付、重复支付、已取消订单 |
| 订单详情 | `GET /api/orders/{orderNo}` | Session Cookie（CUSTOMER） | 路径参数 `orderNo` | 订单详情 | 跨用户访问拒绝 |
| 取消订单 | `POST /api/orders/{orderNo}/cancel` | Session Cookie + CSRF（CUSTOMER） | 路径参数 `orderNo` | 成功响应 | 已支付/已发货订单不得非法取消 |
| 店铺商品与库存 | `GET /api/store/products` | Session Cookie（STORE_EMPLOYEE） | — | 商品及库存列表 | 店铺归属、空列表 |
| 商品创建 | `POST /api/store/products` | Session Cookie + CSRF（STORE_EMPLOYEE） | `skuId`, `name`, `description`, `price`, `availableQuantity` | 商品结果 | 跨店铺访问、字段校验 |
| 商品资料更新 | `PUT /api/store/products/{skuId}` | Session Cookie + CSRF（STORE_EMPLOYEE） | `name`, `description`, `price` | 商品结果 | 跨店铺访问、字段校验 |
| 商品上下架 | `PUT /api/store/products/{skuId}/status` | Session Cookie + CSRF（STORE_EMPLOYEE） | `status` | 商品结果 | 非法状态、跨店铺访问 |
| 库存更新 | `PUT /api/store/products/{skuId}/inventory` | Session Cookie + CSRF（STORE_EMPLOYEE） | `availableQuantity` | 库存结果 | 库存下限、锁定库存不被破坏 |
| 店铺订单 | `GET /api/orders/store` | Session Cookie（STORE_EMPLOYEE） | 当前账号绑定店铺 | 所属店铺子订单 | 不得读取其他店铺订单 |
| 发货 | `POST /api/orders/{orderNo}/ship` | Session Cookie + CSRF（STORE_EMPLOYEE） | `company`, `trackingNo` | 物流记录 | 非所属店铺、非法状态、重复发货 |
| 创建客服会话 | `POST /api/customer-service/sessions` | Session Cookie（CUSTOMER） | — | `sessionId`, `createdAt` | 用户身份不能为空 |
| 客服会话/历史 | `GET /api/customer-service/sessions/{sessionId}`, `/messages` | Session Cookie（CUSTOMER） | 路径参数 `sessionId` | 会话或消息列表 | 用户本人会话、跨用户访问拒绝 |
| 发送客服消息 | `POST /api/customer-service/sessions/{sessionId}/messages` | Session Cookie + CSRF（CUSTOMER）, `Content-Type: application/json`, `Accept: text/event-stream` | `{ "content": "..." }` | SSE `message`、`complete` 事件 | 用户本人会话、内容长度、服务不可用 |
| 客服订单/物流工具 | `GET /api/customer-service/orders/{orderNo}`、`.../logistics` | Session Cookie（CUSTOMER） | 路径参数 `orderNo` | 订单/物流数据 | 跨用户访问拒绝、无物流记录 |

客服 SSE 消息接口为 `POST /api/customer-service/sessions/{sessionId}/messages`，请求体为 `{ "content": "..." }`，应检查 `message`、`complete` 和错误事件的实际行为。

## 5. 测试执行顺序

### 阶段 1：环境和构建

1. 确认使用 PowerShell 7，MySQL、Redis 运行正常。
2. 按项目约定启动后端和前端，并记录服务地址及安全的配置摘要。
3. 执行前端构建。
4. 执行后端测试。
5. 确认 Flyway 迁移成功。
6. 使用专用测试库准备店铺和测试 SKU。

不得使用 `xinghe_trade` 执行重置、删除、更新或压测。切换数据库时必须在应用启动窗口设置 `MYSQL_DATABASE` 并重启应用；迁移与店铺/SKU 数据准备都完成后才能跑用例。测试写操作只能落入本次阶段 F 专用库。

### 阶段 2：最小交易闭环

只验证一条完整链路：

```text
搜索商品
  -> 加入购物车
  -> 修改数量
  -> 结算
  -> 支付父订单
  -> 店铺查询子订单
  -> 店铺发货
  -> 用户查询物流
```

这条链路通过后，再执行幂等和异常测试。

### 阶段 3：P0 回归

覆盖：

- 重复结算。
- 重复支付。
- 支付子订单是否被正确拒绝。
- 非所属店铺读取和发货。
- 用户读取其他用户订单和客服会话。
- 无库存、下架商品、非法数量。
- 取消已支付、已发货订单。
- 刷新页面后的客服历史恢复；订单和购物车状态按当前前端实际持久化能力验收，不假定本地永久保存。

### 阶段 4：P1 和体验测试

覆盖商品详情、订单历史、库存边界、空页面、错误提示、重复点击和移动端布局。对未实现或不在本阶段范围内的能力明确记录结论。

## 6. 每个用例的通过标准

每个测试用例必须记录：

| 字段 | 要求 |
|---|---|
| 用例编号 | 如 `F-P0-TRADE-01` |
| 前置数据 | 用户、店铺、SKU、库存 |
| 操作步骤 | 可重复执行 |
| 预期 HTTP | 状态码和关键字段 |
| 预期数据库状态 | 订单、库存、幂等表等 |
| 实际结果 | 原始响应或摘要 |
| 结论 | 通过/失败/阻塞/不纳入 |
| 证据文件 | 请求、响应、SQL 输出、截图 |

### 交易闭环数据库检查

至少检查：

- 父订单和子订单数量。
- 订单状态分布。
- 支付状态是否只发生一次状态迁移。
- 库存锁定、售出和剩余数量。
- 幂等记录数量及状态。
- 发货后的物流记录。
- 店铺查询结果仅包含该店铺的子订单。

脚本中的 `Successful` 和 `DuplicatePayments` 只能作为辅助信息，不能替代数据库判定。对每个关键写入用例记录目标库名称并确认连接目标；结果判定使用只读 SQL 证据。

## 7. 缺陷分级与处理

| 级别 | 定义 | 处理 |
|---|---|---|
| Blocker | 无法启动、无法结算、数据越权、订单或库存损坏 | 必须修复 |
| P0 | 核心交易链路失败、5xx、错误状态迁移 | 必须修复 |
| P1 | 非主流程功能失败或重要异常分支缺失 | 修复或明确遗留 |
| P2 | 文案、布局、非关键交互问题 | 记录后安排 |

修复后必须重新执行受影响用例及最小回归集，不能只根据代码变更宣称通过。若需修改业务代码，依照项目约定先提交并记录新的 SHA，再开始该版本的正式验收。

## 8. 交付物

结果目录按阶段和用例隔离，例如：

```text
stage-f-results/
  baseline/
  p0-trade/
  p0-customer-service/
  p0-store/
  p1/
  final-report/
```

每个主线目录至少包含：

- `summary.json`
- 原始请求和响应
- 构建与测试日志
- 数据库查询输出
- 关键页面截图
- 缺陷清单
- 结论表

最终报告必须记录：

- 被测 commit SHA。
- 工作区是否包含未提交改动及其纳入范围。
- 前端构建结果。
- 后端测试结果。
- P0/P1 完成情况。
- 未执行和遗留项目。
- 测试库名称。
- 关键数据库证据。
- 已知限制和后续建议。

## 9. 阶段完成条件

阶段 F 只有同时满足以下条件才可标记为“验收完成”：

- 前端构建通过。
- 后端测试通过，或所有失败项已有明确解释。
- P0 交易闭环通过。
- P0 权限和越权检查通过。
- P0 客服主线通过，或已明确记录外部依赖导致的阻塞。
- 没有 Blocker 或未解释的 P0 缺陷。
- 数据库证据与 HTTP 结果一致。
- 所有未完成项均已列入遗留清单。
- 报告中已记录 commit、配置、测试库和结果目录。

最终结论建议只使用以下三种：

- **通过**：P0 全部通过，无 Blocker/P0 缺陷。
- **有条件通过**：P0 通过，但存在已接受的 P1 或遗留项。
- **不通过**：P0 失败、存在越权/数据错误，或关键证据缺失。