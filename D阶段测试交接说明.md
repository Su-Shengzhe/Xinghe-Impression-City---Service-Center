# 阶段 D 测试交接说明（给执行代理）

> **前置阅读**：项目根 `AGENTS.md` —— 工具、数据库、判定口径的通用约定都在那里，本文件只讲"这一阶段具体怎么测"。
> **用例定义来源**：`D阶段测试计划.md`。若仓库中还没有该文件，请先请负责人补入，本文件只覆盖已确认的 D-02 / D-03 / D-04。

---

## 1. 任务

按用例逐个执行阶段 D 的并发专项测试，**为每个用例产出可复核的证据**（原始响应 + 数据库状态），并给出结论表。代理负责"执行 + 取证 + 初步判读"，**通过与否由负责人确认**。

---

## 2. 环境快照（开始前先记录，填进最终报告）

| 项 | 取值方式 | 本次值 |
|---|---|---|
| 被测版本（commit） | `git -C "D:\codex-project\Xinghe Impression City Service Center" rev-parse HEAD` | |
| 应用连接的库 | 启动窗口里的 `$env:MYSQL_DATABASE` | |
| 后端端口 | `.env` 的 `SERVER_PORT` | 8081 |
| PowerShell | `$PSVersionTable.PSVersion` | 必须 7.x |
| JDK | `java -version` | |
| Maven | `mvn -v` | |
| MySQL | `SELECT VERSION();` | 8.0.x |
| Redis | `redis-cli ping` | |
| 测试脚本 | `git -C ... log -1 -- scripts/` 或文件时间 | |

> 没有 commit SHA 的报告等于没有版本信息——先确认 `git -C` 能取到值，取不到就先修脚本（见 `AGENTS.md` 第 7 节第 2 条）。

---

## 3. 用例 ↔ 库 ↔ 脚本 ↔ 用户 对照表

| 用例 | 名称 | 专用库 | 脚本 | 专用用户 |
|---|---|---|---|---|
| D-02 | 库存并发扣减 | `xinghe_stage_d` | `scripts/stage-d-concurrency.ps1` | `stage-d-user` |
| D-03 | 同 Token 并发支付 | `xinghe_stage_d_d03` | `scripts/stage-d-payment-race.ps1` | `stage-d-same-token` |
| D-04 | 不同 Token 并发支付 | `xinghe_stage_d_d04` | `scripts/stage-d-payment-race.ps1` | `stage-d-different-token` |
| D-01 等 | 待按测试计划补入 | | | |

**一个用例一个库，不要复用。** 复用会污染"次数类"断言（订单数、幂等表行数、库存锁定数）。脚本现在只接受真实登录会话：D-02 的每个并发请求注册唯一顾客账号，D-03/D-04 的每个请求独立登录同一个顾客账号；不再使用 `X-User-Id` / `X-Store-Id`。

### 账号准备

员工账号不能通过顾客注册取得。若目标库已有店铺 `id=1`，可在**启动应用的同一 PowerShell 7 窗口**显式启用一次受信任播种：

```powershell
$env:XINGHE_AUTH_EMPLOYEE_SEED_ENABLED = "true"
$env:XINGHE_AUTH_EMPLOYEE_SEED_USERNAME = "stage-d-employee"
$env:XINGHE_AUTH_EMPLOYEE_SEED_PASSWORD = $env:XINGHE_STAGE_D_EMPLOYEE_PASSWORD
$env:XINGHE_AUTH_EMPLOYEE_SEED_STORE_ID = "1"
```

应用配置使用 `XINGHE_AUTH_EMPLOYEE_SEED_*` 映射到 `xinghe.auth.employee-seed.*`。账号已存在时播种会 fail-fast，不会覆盖密码或角色。启动完成后关闭该开关并重启，再运行脚本。顾客密码只通过环境变量 `XINGHE_TEST_PASSWORD` 或脚本参数提供，不能写入仓库。

---

## 4. 通用前置检查（每个库、每个用例开始前都要做，全绿才继续）

```sql
-- 把 <库> 换成对照表里的专用库
USE <库>;

-- 1) 迁移到位
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;   -- V1/V2 都 success=1
SHOW TABLES;                                                                               -- 期望 11 张表

-- 2) 店铺存在且营业
SELECT * FROM store WHERE id = 1;                                                          -- 期望 1 行，status='OPEN'

-- 3) 测试 SKU 就绪
SELECT id, name, status, price FROM product WHERE id = 990001;                             -- 期望 ON_SALE（注意列名是 id，没有 sku_id）
SELECT sku_id, available_quantity, locked_quantity, sold_quantity, version
  FROM product_inventory WHERE sku_id = 990001;

-- 4) 残留数据符合预期（每轮开始前都应接近 0）
SELECT COUNT(*) FROM trade_order;
SELECT COUNT(*) FROM trade_order_item;
SELECT COUNT(*) FROM cart_item;
SELECT COUNT(*) FROM payment_idempotency;
```

**还要确认应用真的连在这个库上**（没有健康检查接口）：用 `X-Store-Id: 1` 建一次 SKU 990001，然后回目标库查 `product` —— 查不到就说明应用连的是别的库，**先切库重启，不要继续测**。

---

## 5. 用例执行卡

### D-02 库存并发扣减

**前置**：库 `xinghe_stage_d`（注意：该库的 `store.id=1` 是手工插入的「星河旗舰店」，**已存在，不要再插**）；SKU 990001 就绪；`InitialStock = 10`。

```powershell
$root = "D:\codex-project\Xinghe Impression City Service Center"
$env:MYSQL_HOST = "localhost"; $env:MYSQL_PORT = "3306"
$env:MYSQL_DATABASE = "xinghe_stage_d"
$env:MYSQL_USERNAME = "root"; $env:MYSQL_PASSWORD = "<数据库密码>"

& "$root\scripts\stage-d-concurrency.ps1" -ResetDatabase -DbName "xinghe_stage_d" `
  -SkuId 990001 -UserId "stage-d-user" `
  -StoreUsername "stage-d-employee" -StorePassword $env:XINGHE_STAGE_D_EMPLOYEE_PASSWORD `
  -CustomerPassword $env:XINGHE_TEST_PASSWORD `
  -Requests 100 -Rounds 3 -InitialStock 10 `
  -OutputDirectory "$root\stage-d-results\stage-d-d02"
```

**期望（HTTP）**：每轮 100 个请求里**恰好 10 个 200**，其余 409（`商品库存不足` / `库存并发冲突，请稍后重试`），**没有 5xx**。

**期望（数据库）**：

```sql
SELECT sku_id, available_quantity, locked_quantity, sold_quantity,
       available_quantity + locked_quantity + sold_quantity AS total
  FROM product_inventory WHERE sku_id = 990001;   -- total 恒为 10
SELECT status, COUNT(*) FROM trade_order GROUP BY status;
SELECT SUM(quantity) FROM trade_order_item WHERE sku_id = 990001;   -- = 10
```

**参考基线**（2026-10-08 17:25 实测）：3 轮均 `10 成功 / 90 失败`，`Oversold = 0`，平均 190–220ms，P95 218–296ms；第 1 轮出现 2 次乐观锁冲突。落库为 10 个父单 + 10 个子单 + 10 条明细，`locked 10`。

**常见误判**：`Failed: 90` 看着刺眼，但必须回看 `round-N-raw.json` 确认它们全是 409 业务拦截；出现 500 才算缺陷。

---

### D-03 同 Token 并发支付

**前置**：库 `xinghe_stage_d_d03`；先按 `AGENTS.md` 4.3 用 CSRF + `/api/auth/register` 注册顾客账号 `stage-d-same-token`，再用该账号建一笔待支付订单（cart → checkout），并让建单与支付使用同一真实登录账号，记下 **父单号**。

```powershell
& "$root\scripts\stage-d-payment-race.ps1" -BaseUrl "http://localhost:8081" `
  -UserId "stage-d-same-token" -LoginPassword $env:XINGHE_TEST_PASSWORD -OrderNo "<父单号>" `
  -Requests 20 -Rounds 1 -TokenMode "same-token" `
  -OutputDirectory "$root\stage-d-results\stage-d-d03"
```

**期望（HTTP）**：20 个请求**全部 200**。同 Token 的幂等重放按设计返回 200（`OrderService#payWithDatabaseToken` 命中 `SUCCEEDED` 后正常返回），这是正确行为。

> ⚠️ **`summary.json` 里的 `DuplicatePayments: 19` 不是缺陷**，它只是脚本用 `成功数 - 1` 推断出来的假阳性。**判定必须看数据库。**

**期望（数据库）**：

```sql
SELECT token, order_no, status, result_status FROM payment_idempotency;   -- 恰好 1 行，SUCCEEDED
SELECT order_no, parent_order_no, status FROM trade_order;                -- 父子单都 PAID
SELECT sku_id, available_quantity, locked_quantity, sold_quantity FROM product_inventory WHERE sku_id = 990001;
-- 期望 avail 9 / locked 0 / sold 1
```

**参考基线**（2026-10-08 18:01 实测）：20 请求全 200，平均 173.6ms；幂等表 1 行；父子单 `PAID`；库存 `avail 9 / locked 0 / sold 1` → **通过**。

---

### D-04 不同 Token 并发支付

**前置**：库 `xinghe_stage_d_d04`；先注册顾客账号 `stage-d-different-token`，再用该账号建一笔待支付订单；登录账号必须与后续支付请求的 `-UserId` 完全一致。

```powershell
& "$root\scripts\stage-d-payment-race.ps1" -BaseUrl "http://localhost:8081" `
  -UserId "stage-d-different-token" -LoginPassword $env:XINGHE_TEST_PASSWORD -OrderNo "<父单号>" `
  -Requests 20 -Rounds 1 -TokenMode "different-token" `
  -OutputDirectory "$root\stage-d-results\stage-d-d04"
```

**`-Rounds` 必须是 1**：订单一旦 `PAID`，第 2 轮起所有请求都会 409，记录没有意义。

**期望（HTTP）**：**1 × 200 + 19 × 409**，报文为 `订单已支付，不能重复确认`。

**为什么是 1 而不是 20**：不同 Token 时，token 幂等**不提供保护**，靠的是订单行锁 + 状态条件更新 —— `OrderService#payWithDatabaseToken` 先 `selectForUpdate(orderNo)`，输家拿到锁后读到 `PAID`，且幂等表里成功记录属于**别的 token**，于是抛异常（见 `OrderService.java:487-494`、`:496-508`）。

**期望（数据库）**：

```sql
SELECT token, order_no, status, result_status FROM payment_idempotency;   -- 恰好 1 行（输家的 PROCESSING 行随事务回滚）
SELECT order_no, parent_order_no, status FROM trade_order;                -- 父子单都 PAID，只迁移一次
SELECT sku_id, available_quantity, locked_quantity, sold_quantity FROM product_inventory WHERE sku_id = 990001;
-- 期望 avail 9 / locked 0 / sold 1
```

**状态**：截至 2026-10-08 尚未执行。该库已具备 `store` + SKU 990001（库存 10），**待创建订单后执行**。

**常见误判**：出现 500（死锁 / 锁等待超时）必须单独列出并复测 —— 那是系统缺陷，不是"正确拦截"。

---

## 6. 交付物

每个用例：

- `summary.json`、`round-N-raw.json`（脚本已自动产出）
- 第 5 节里各条 SQL 的**原始输出**（不要只写"正常"，要贴数字）
- 结论表里的一行

最终汇总一份报告，含：环境快照（第 2 节）+ 用例结论表 + 已知问题清单。

结论表模板：

| 用例 | 期望 | 实测(HTTP) | 实测(数据库) | 结论 | 证据文件 |
|---|---|---|---|---|---|
| D-02 | 恰好 10 成功、0 超卖 | | | | `stage-d-results/stage-d-d02/...` |
| D-03 | 幂等表 1 行、只迁移 1 次 | | | | `stage-d-results/stage-d-d03/...` |
| D-04 | 1×200 + 19×409、只迁移 1 次 | | | | `stage-d-results/stage-d-d04/...` |

---

## 7. 禁止事项（重申）

1. 不碰 `xinghe_trade` 和任何非本用例库；不执行 `DROP` / `TRUNCATE`。
2. `-ResetDatabase` 只在专用用例库使用。
3. 测试期间不改业务代码；必须改就先提交并记录 SHA。
4. 不用 Windows PowerShell 5.1。
5. **不把脚本 summary 字段直接当结论**，必须给数据库证据。
6. 不把带 `COMMIT` 的重置语句"包在事务里试一下"。

---

## 8. 卡住了怎么办（故障速查）

| 现象 | 原因 | 处理 |
|---|---|---|
| `术语 '\scripts\...' 不会被识别` | 新会话里 `$root` 为空 | 先 `$root = "D:\codex-project\..."`，或改用字面路径 |
| `术语 'CREATE' 不会被识别` | SQL 敲进了 PowerShell | 用 DBeaver SQL 编辑器，或 `mysql -e "..."` |
| `1062 - Duplicate entry '1' for key 'store.PRIMARY'` | `store.id=1` 已存在 | 先 `SELECT` 确认，**不要重建或覆盖**；已存在就跳过 |
| `商品不存在或已下架` | 目标库没做数据准备，或应用连的不是目标库 | 按 `AGENTS.md` 3.3 / 3.4 处理 |
| `购物车为空` | 前面的加购已经失败 | 先看加购的响应体 |
| `NativeCommandError` + mysql 密码警告 | 用了 PowerShell 5.1 | 换 `pwsh` 重跑 |
| `请支付父单` | `-OrderNo` 传了子单号 | 用 `data[0]` 或 `parent_order_no IS NULL` 的那条 |
| `无权支付该订单`(403) | `-UserId` 与建单用户不一致 | 两者必须完全相同 |
| `店铺不存在或未营业` | 店铺行缺失或 `status != 'OPEN'` | 按 `AGENTS.md` 3.3 补 |
