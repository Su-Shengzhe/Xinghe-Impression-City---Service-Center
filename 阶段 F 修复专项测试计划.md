# 阶段 F 修复专项测试计划

## 1. 测试目的

本计划用于验收阶段 F 本轮修复，不重新定义《D:\codex-project\Xinghe Impression City Service Center\阶段 F 联调与验收计划.md》中的完整交易、履约和客服范围。

本轮重点验证以下三个问题已经被修复，且没有破坏阶段 F 既有主线：

| 编号 | 修复点 | 主要风险 | 核心判定 |
|---|---|---|---|
| FIX-01 | 客服商品详情接口的 `skuId` 正数校验和统一 HTTP 400 响应 | `0`、负数等非法路径参数进入业务查询，或被错误返回 500 | 非正数在 Controller/参数校验层被拒绝，响应为 400，商品查询服务不被调用 |
| FIX-02 | 商品资料更新使用只包含可编辑字段的 `ProductUpdateRequest` | 编辑商品资料时误读、覆盖或重置库存，尤其是锁定库存和已售库存 | 商品资料改变，库存数量和版本字段保持不变 |
| FIX-03 | 店铺订单物流由逐单查询改为一次批量 `IN` 查询 | 订单数量增加后出现物流查询 N+1，或批量结果错序、错配 | 一个订单列表只触发一次物流批量查询，返回顺序与订单顺序一致，空订单不查物流 |

本计划的目标是形成可复核的自动化、接口和数据库证据。计划执行期间不修改业务代码，不覆盖已有 `stage-f-results` 证据目录。

## 2. 被测范围与基线

### 2.1 被测文件

本轮重点文件如下：

- `D:\codex-project\Xinghe Impression City Service Center\hd\src\main\java\com\xinghe\trade\customer\CustomerServiceController.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\main\java\com\xinghe\trade\product\ProductQueryService.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\main\java\com\xinghe\trade\product\ProductManagementController.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\main\java\com\xinghe\trade\product\ProductManagementService.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\main\java\com\xinghe\trade\order\OrderService.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\main\java\com\xinghe\trade\order\TradeOrderMapper.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\test\java\com\xinghe\trade\customer\CustomerServiceControllerTest.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\test\java\com\xinghe\trade\product\ProductManagementServiceTest.java`
- `D:\codex-project\Xinghe Impression City Service Center\hd\src\test\java\com\xinghe\trade\order\OrderServiceTest.java`

### 2.2 验收前冻结记录

正式执行前必须在 PowerShell 7 中保存以下信息。工作区已有未提交改动和历史证据目录时，必须明确哪些改动属于本次被测版本，不能只用当前 `HEAD` 代表整个被测状态。

```powershell
$root = "D:\codex-project\Xinghe Impression City Service Center"

$PSVersionTable.PSVersion
git -C $root rev-parse HEAD
git -C $root status --short
git -C $root diff --stat
git -C $root diff --check
```

当前已知基线结果（来自本阶段已有执行记录）为：

- 重点测试：15/15 通过；
- 后端完整 Maven 测试：51/51 通过；
- `git diff --check` 通过；
- 既有未提交改动和 `stage-f-results` 证据目录需要继续保留。

上述结果在正式签署前仍应按最终工作区状态重跑或引用对应日志，不能将历史结果自动等同于最终版本结果。

## 3. 测试环境与数据隔离

### 3.1 环境要求

| 项目 | 要求 |
|---|---|
| 命令行 | PowerShell 7（`pwsh`），不得使用 Windows PowerShell 5.1 |
| 后端 | Spring Boot，`http://localhost:8081` |
| 前端 | Vue/Vite，`http://localhost:5173`；仅用于阶段 F 回归时启动 |
| MySQL | 3306，必须使用阶段 F 专用库 |
| Redis | 6379，购物车、会话和现有交易流程依赖 |
| RabbitMQ | 本专项可关闭消息功能；若启用，vhost 与测试库同名 |
| Elasticsearch | 本专项可关闭，商品查询使用 MySQL 回退路径即可 |

推荐使用全新的专用库 `xinghe_stage_f_fix`，不要对 `xinghe_trade` 或任何其他项目库执行写操作、清理或重置。如果复用已有 `xinghe_stage_f`，必须先记录库内数据快照，不得将旧证据和新一轮结果混写。

### 3.2 启动配置

在启动后端的同一个 PowerShell 7 窗口设置数据库和开关，并重启应用：

```powershell
$root = "D:\codex-project\Xinghe Impression City Service Center"
$env:MYSQL_DATABASE = "xinghe_stage_f_fix"
$env:SPRING_RABBITMQ_VIRTUAL_HOST = "xinghe_stage_f_fix"
$env:XINGHE_MESSAGING_ENABLED = "false"
$env:XINGHE_ELASTICSEARCH_ENABLED = "false"
$env:XINGHE_SEED_ENABLED = "false"

Set-Location "$root\hd"
mvn -s maven-settings.xml spring-boot:run
```

如果启用 RabbitMQ，必须确认 `xinghe_stage_f_fix` vhost 和队列拓扑干净且参数一致；不得因本专项验证把消息发送到默认 `/` vhost。

### 3.3 数据准备

1. 创建专用库，并确认应用启动后的 Flyway 迁移完成。只对该专用库执行建库和准备操作。
2. 先查询店铺，再插入缺失的营业店铺 `id=1`；需要跨店隔离时准备 `id=2`，状态均为 `OPEN`。
3. 通过商品管理接口创建测试商品，推荐使用：
   - SKU `990020`：店铺 1，在售，初始可用库存 10；
   - SKU `990021`：店铺 2，在售，用于跨店访问验证。
4. 为 SKU `990020` 准备一笔待支付订单，先记录库存快照，再执行商品资料更新，使 `locked_quantity` 非零。这样可以验证资料编辑不会覆盖待支付订单锁定的库存。
5. 为店铺订单批量场景准备至少两笔店铺 1 的子订单；其中可以有一笔已发货并有物流记录，另一笔无物流记录，以验证物流缺失时仍能按订单返回 `null` 物流视图。
6. 为无订单场景保留店铺 2，或使用另一个没有子订单的营业店铺。不得通过删除非本用例库数据制造空结果。

创建商品的请求体示例：

```powershell
$body = @{
  skuId = 990020
  name = "Stage F Fix Product"
  description = "Product detail and inventory isolation test"
  price = 12.50
  availableQuantity = 10
} | ConvertTo-Json -Compress

Invoke-WebRequest -Method Post -Uri "http://localhost:8081/api/store/products" `
  -Headers @{ "X-Store-Id" = "1" } `
  -ContentType "application/json" -Body $body -SkipHttpErrorCheck
```

准备待支付库存锁定时，必须通过购物车和结算接口完成，不直接伪造业务订单：

```powershell
$user = "stage-f-fix-user"
$headers = @{ "X-User-Id" = $user }

Invoke-WebRequest -Method Post -Uri "http://localhost:8081/api/cart/items" `
  -Headers $headers -ContentType "application/json" `
  -Body '{"skuId":990020,"quantity":2}' -SkipHttpErrorCheck

$checkout = Invoke-WebRequest -Method Post -Uri "http://localhost:8081/api/orders/checkout" `
  -Headers ($headers + @{ "Idempotency-Token" = "stage-f-fix-checkout-$user" }) `
  -SkipHttpErrorCheck
```

执行每个接口前后都保存原始 HTTP 响应。请求头中不得记录数据库密码或其他敏感凭据。

## 4. 自动化测试计划

### 4.1 商品详情参数校验

| 用例 | 操作 | 预期 HTTP 与响应 | 数据/调用断言 |
|---|---|---|---|
| F-FIX-UNIT-01 | 使用 `GET /api/customer-service/products/0` | HTTP 400；响应为 `code=-1`、`message=请求参数无效`、`data=null` | `ProductQueryService` 无任何交互 |
| F-FIX-UNIT-02 | 使用 `GET /api/customer-service/products/-1` | HTTP 400，统一错误结构 | `ProductQueryService` 无任何交互 |
| F-FIX-API-01 | 使用正数且存在的 SKU 查询详情 | HTTP 200；`code=0`，返回商品详情 | 只返回在售商品，字段与数据库一致 |
| F-FIX-API-02 | 使用正数但不存在或已下架的 SKU 查询详情 | HTTP 400；返回“商品不存在或已下架” | 不得返回 500，不得泄露内部异常 |

自动化重点是确认 `@Positive` 触发的 `HandlerMethodValidationException` 进入统一 400 分支，而不是落入通用 500 分支。非数字路径参数可作为 P1 健壮性补充用例执行；若结果不是 400，应单独记录为参数类型转换缺陷，不与本轮非正数修复混淆。

### 4.2 商品资料更新与库存隔离

| 用例 | 操作 | 预期 HTTP 与响应 | 数据库断言 |
|---|---|---|---|
| F-FIX-UNIT-03 | 用所属店铺更新商品名称、描述和价格 | HTTP 200；商品资料为新值 | `productMapper.updateById` 调用一次；`ProductInventoryMapper` 无交互 |
| F-FIX-UNIT-04 | 使用其他店铺更新 SKU | HTTP 400，提示店铺商品不存在 | 商品和库存均不改变，禁止跨店写入 |
| F-FIX-API-03 | 保存库存快照后调用 `PUT /api/store/products/990020` | HTTP 200；只返回更新后的商品资料 | `available_quantity`、`locked_quantity`、`sold_quantity`、`version` 均与更新前完全一致 |
| F-FIX-API-04 | 在编辑请求中额外发送 `availableQuantity`、`skuId` 等非编辑字段 | 默认配置下应忽略额外字段并成功更新；若环境配置为禁止未知字段，可返回 400 | 无论返回 200 还是 400，库存不得变化，且不得出现 5xx |
| F-FIX-API-05 | 通过专用库存接口更新可用库存 | HTTP 200 | 只有 `PUT /api/store/products/{skuId}/inventory` 能改变可用库存；该回归不应被资料更新逻辑影响 |

商品资料更新的数据库证据至少保存以下查询的更新前、更新后完整输出：

```sql
SELECT p.id, p.store_id, p.name, p.description, p.price, p.status, p.updated_at,
       i.available_quantity, i.locked_quantity, i.sold_quantity, i.version, i.updated_at
  FROM product p
  LEFT JOIN product_inventory i ON i.sku_id = p.id
 WHERE p.id = 990020;
```

验收时允许商品表的 `name`、`description`、`price`、`updated_at` 变化；库存表的数量和 `version` 不允许因资料更新而变化。若测试先创建了待支付订单，`locked_quantity` 必须保持锁定状态。

### 4.3 店铺订单物流批量查询

| 用例 | 操作 | 预期结果 | 查询/映射断言 |
|---|---|---|---|
| F-FIX-UNIT-05 | 准备两个订单，物流 mapper 返回顺序与订单顺序相反 | 返回仍按订单 mapper 的原始顺序排列 | 物流 mapper 只调用一次 `selectList(IN ...)`，不调用逐单 `selectOne` |
| F-FIX-UNIT-06 | 订单列表为空 | `GET /api/orders/store` 返回 HTTP 200、空数组 | 完全不调用物流 mapper，不执行无意义物流查询 |
| F-FIX-UNIT-07 | 模拟重复订单号的物流记录 | 使用第一条记录建立映射 | 后续重复记录不覆盖第一条记录，结果稳定 |
| F-FIX-API-06 | 店铺 1 查询自己的子订单 | HTTP 200；订单数量、顺序和物流字段正确 | 只出现店铺 1 的子订单；有物流的订单返回公司、运单号和状态，无物流订单返回空物流值 |
| F-FIX-API-07 | 无子订单的营业店铺查询 | HTTP 200、`data=[]` | 不发生物流查询；不得返回 500 |
| F-FIX-API-08 | 店铺 2 查询店铺 1 的订单或尝试处理其订单 | 查询结果不包含店铺 1 订单；修改操作被拒绝 | 不得发生跨店数据泄露或状态变化 |

重复物流记录只在单元测试中验证“首条记录优先”。数据库中的 `logistics_record.order_no` 具有唯一约束，运行时数据库不应通过非法数据准备制造重复记录。

### 4.4 阶段 F 回归

修复专项通过后执行最小阶段 F 回归：

1. 商品搜索和商品详情；
2. 商品资料更新、上下架和库存专用更新；
3. 购物车、结算、父订单支付；
4. 店铺查看子订单、发货、用户侧物流查询；
5. 客服订单、物流和商品查询；
6. 用户/店铺跨主体访问拒绝；
7. 重复发货、取消后支付、重复支付等已有 P1 回归。

本专项不重新纳入评论管理、评论 AI、客服运营端会话管理等《D:\codex-project\Xinghe Impression City Service Center\阶段 F 联调与验收计划.md》明确排除的功能。

## 5. 执行顺序与命令

### 5.1 后端自动化

在 `D:\codex-project\Xinghe Impression City Service Center\hd` 执行：

```powershell
$root = "D:\codex-project\Xinghe Impression City Service Center"
Set-Location "$root\hd"

# 先执行本轮相关测试类，保存完整控制台输出
mvn -s maven-settings.xml -Dtest=CustomerServiceControllerTest,ProductManagementServiceTest,OrderServiceTest test

# 再执行完整后端回归
mvn -s maven-settings.xml test
```

预期：重点测试无失败、无错误；完整后端测试为 51/51 或与最终代码状态相符的完整测试数，失败项必须逐项解释，不得只看 Maven 退出码。

### 5.2 前端构建回归

```powershell
$root = "D:\codex-project\Xinghe Impression City Service Center"
Set-Location "$root\qd"
npm run build
```

预期：Vite 生产构建成功。前端构建不是本轮三个后端修复的充分证据，但属于阶段 F 完成条件中的回归项。

### 5.3 代码和结果检查

```powershell
$root = "D:\codex-project\Xinghe Impression City Service Center"
git -C $root diff --check
git -C $root status --short
```

不得提交或覆盖 `D:\codex-project\Xinghe Impression City Service Center\.env`、既有 `stage-f-results` 证据和其他临时产物。

## 6. 数据库证据

所有 SQL 只读核验必须指向本轮专用库，并将命令和原始输出一起保存。建议至少执行：

```sql
SELECT DATABASE();

SELECT version, description, success
  FROM flyway_schema_history
 ORDER BY installed_rank;

SELECT p.id, p.store_id, p.name, p.price, p.status,
       i.available_quantity, i.locked_quantity, i.sold_quantity, i.version
  FROM product p
  LEFT JOIN product_inventory i ON i.sku_id = p.id
 WHERE p.id IN (990020, 990021);

SELECT order_no, parent_order_no, user_id, store_id, status, amount
  FROM trade_order
 WHERE store_id IN (1, 2)
 ORDER BY created_at, order_no;

SELECT order_no, company, tracking_no, status
  FROM logistics_record
 WHERE order_no IN (<本轮实际子订单号>)
 ORDER BY order_no;
```

数据库判定重点：

- `SELECT DATABASE()` 必须是本轮专用库；
- Flyway 所需版本全部 `success=1`；
- 商品资料更新前后库存数量和 `version` 一致；
- 店铺订单只属于请求店铺；
- 物流记录只挂在正确的子订单；
- 无额外订单、库存或物流脏写入；
- HTTP 响应与数据库状态一致。

## 7. 证据目录与交付物

本轮复测建议使用独立目录 `D:\codex-project\Xinghe Impression City Service Center\stage-f-results\after-fix-revalidation\`，不得覆盖已有 `D:\codex-project\Xinghe Impression City Service Center\stage-f-results\after-fix\` 或 `final-report` 文件。

建议目录内容：

```text
D:\codex-project\Xinghe Impression City Service Center\stage-f-results\after-fix-revalidation\
  00-baseline.txt
  01-focused-backend-test.log
  02-full-backend-test.log
  03-frontend-build.log
  04-detail-invalid-0.json
  05-detail-invalid-negative.json
  06-detail-valid.json
  07-product-before.sql.txt
  08-product-update.json
  09-product-after.sql.txt
  10-store-orders-batch.json
  11-store-orders-empty.json
  12-database-evidence.sql.txt
  summary.md
  defect-list.md
```

`00-baseline.txt` 必须包含 commit SHA、工作区状态、测试库名、后端地址、开关摘要和执行时间；不得包含数据库密码。

最终结论表使用以下字段：

| 用例 | 期望 | 实测 HTTP | 实测数据库/调用次数 | 结论 | 证据 |
|---|---|---|---|---|---|
| F-FIX-API-01 | 正数在售商品返回 200 |  |  |  |  |
| F-FIX-API-02 | 非法/不存在商品不返回 5xx |  |  |  |  |
| F-FIX-API-03 | 编辑资料不改变库存 |  |  |  |  |
| F-FIX-UNIT-05 | 一个订单列表一次批量查物流 |  |  |  |  |
| F-FIX-UNIT-06 | 空订单不查物流 |  |  |  |  |
| F-FIX-API-08 | 跨店访问被拒绝 |  |  |  |  |

## 8. 通过标准与缺陷分级

### 8.1 专项通过标准

同时满足以下条件才可将本轮修复标记为通过：

1. `skuId=0` 和负数商品详情请求均为 HTTP 400，且不进入商品查询服务；
2. 正常商品详情、商品不存在/下架分支均无未解释的 5xx；
3. 商品资料更新只能改变商品资料，库存 `available_quantity`、`locked_quantity`、`sold_quantity` 和 `version` 均不被误改；
4. 非所属店铺不能编辑商品或读取其订单；
5. 店铺订单物流查询对 N 个订单只执行一次批量查询，返回顺序不变；
6. 空店铺订单不会触发物流查询；
7. 重点测试、后端完整测试和前端构建均通过，或所有偏差均有明确缺陷单和风险接受记录；
8. HTTP、调用次数和数据库证据互相一致；
9. 没有 Blocker、未解释的 P0 或数据损坏问题。

### 8.2 缺陷分级

| 级别 | 示例 | 处理要求 |
|---|---|---|
| Blocker | 应用无法启动、写入错误数据库、商品更新导致库存损坏 | 阻断验收，必须修复 |
| P0 | 非正数请求返回 500、跨店越权、批量查询结果错配、库存被覆盖 | 阻断专项和阶段 F 主线 |
| P1 | 非数字路径参数错误映射、无物流提示异常、日志证据不足 | 修复或在报告中明确遗留 |
| P2 | 文案、格式或非关键页面表现问题 | 记录并安排后续处理 |

修复任何缺陷后，必须重新执行受影响用例、相关自动化测试和最小阶段 F 回归，不能只依据代码审查结论宣称通过。

## 9. 风险与限制

- Mockito 的调用次数断言可以直接证明服务层没有逐单调用，但不能单独代表真实数据库延迟；若需要性能结论，应额外保存应用 SQL 日志或数据库可观测性证据，不得用一次接口耗时推导性能提升比例。
- `logistics_record.order_no` 有唯一约束，因此“重复物流号首条优先”主要通过服务层单元测试验证，不能在正常数据库模型中构造为持久化脏数据。
- 当前工作区存在本阶段前后的未提交改动，正式报告必须同时记录 commit SHA 和工作区状态；若未提交改动纳入被测范围，结论不代表默认分支原始 HEAD。
- 既有阶段 F 结果目录包含历史执行记录，本轮只能新增隔离的复测目录，不能用新结果覆盖旧证据。
