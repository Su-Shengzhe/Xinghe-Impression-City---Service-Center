# AGENTS.md — 星河印象城交易服务中心

本文件是给 AI 编码代理（Codex / DSH 等）的项目约定。**开始任何操作前先读完本文件。**
里面的每一条都来自真实踩坑记录：违反其中任何一条，都可能导致测试结论错误或数据被破坏。

---

## 0. 核心红线（先看这 6 条）

- 一律使用 PowerShell 7（pwsh）。不要用 Windows PowerShell 5.1：
  -SkipHttpErrorCheck 在 5.1 不存在；mysql 的 stderr 警告会被当成致命错误。
- 每个用例用独立专用库（xinghe_stage_d_d03 / _d04 ...）。
  严禁对 xinghe_trade 及任何非本用例库执行写操作。
- 换库 = 两件事：① 在启动应用的窗口设 MYSQL_DATABASE 并重启；
  ② Flyway 自动迁移 + 手工数据准备（INSERT store + 通过接口建 SKU 990001）。
  两者都做完才能跑用例。
- -ResetDatabase 会删订单和购物车，只允许在专用用例库使用。
- 判定结论必须用数据库证据，不能只读脚本的 summary 字段
  （DuplicatePayments 是"成功数-1"的推断，同 Token 幂等重放会返回 200，会假阳性）。
- 结果目录、脚本参数、库名、Commit 一并记录。

下面是对每一条的展开、可复制的命令，以及额外的坑。

---

## 1. 项目结构

| 目录 | 内容 | 启动方式 |
|---|---|---|
| `hd/` | Spring Boot 后端，端口 **8081** | `cd hd; mvn -s maven-settings.xml spring-boot:run` |
| `qd/` | Vue 前端，端口 **5173** | `cd qd; npm install; npm run dev` |
| `scripts/` | 阶段 D 测试脚本 | `pwsh -File .\scripts\xxx.ps1 ...` |
| `stage-d-results/` | 测试结果输出（按用例分目录） | — |

依赖：MySQL 3306（必需）、Redis 6379（必需，购物车与支付幂等依赖它）、RabbitMQ 5672（可选，`XINGHE_MESSAGING_ENABLED=false`）、Elasticsearch 9200（可选）。

连接参数在项目根 `.env`（**不要提交，也不要在输出里打印密码**）。

---

## 2. 工具与中间件约定（最容易踩的两类坑）

### 2.1 命令行与 SQL

- **一律使用 PowerShell 7（`pwsh`）**，不要用 Windows PowerShell 5.1：
  - 5.1 没有 `-SkipHttpErrorCheck` 参数，脚本会在参数绑定阶段直接失败；
  - 5.1 下 `$ErrorActionPreference = "Stop"` + 原生命令写 stderr + `2>&1`，会把 `mysql` 那句「Using a password on the command line interface can be insecure」无害警告升级为致命错误 `NativeCommandError`，脚本当场中止。
  - 自查当前引擎：`$PSVersionTable.PSVersion`（应为 7.x）。窗口标题写着 "Windows PowerShell" 不代表引擎，那只可能是 Windows Terminal 的标签名。
- 调用 mysql 时**不要**用 `--password=`，改用环境变量，从根上消掉那句警告：
  ```powershell
  $env:MYSQL_PWD = "<数据库密码>"   # 见 .env 的 MYSQL_PASSWORD
  mysql -h localhost -P 3306 -u root -e "SELECT 1;"
  ```
- **新开一个 pwsh 会话，之前所有变量都会丢**（`$root`、`$env:MYSQL_DATABASE` 都不例外）。每个新窗口先补：
  ```powershell
  $root = "D:\codex-project\Xinghe Impression City Service Center"
  ```
  变量没设时，`"$root\scripts\x.ps1"` 会展开成 `\scripts\x.ps1`，报「术语 '\scripts\x.ps1' 不会被识别为 cmdlet」。执行前先 `"root=$root"` 打印确认。
- **SQL 不能敲进 PowerShell**：只能交给 SQL 客户端（DBeaver 的 SQL 编辑器）或 `mysql -e "..."`。直接敲会报「术语 'CREATE' 不会被识别为 cmdlet」。

### 2.2 RabbitMQ：vhost 与队列拓扑

**vhost 约定**

- `spring.rabbitmq.virtual-host` **在代码里没有配置**（全局搜 `virtualHost` 零命中），所以应用连哪个 vhost **完全取决于启动窗口的环境变量**，缺省是 `/`。
- **约定：vhost 与数据库同名**，一个用例/阶段一个 vhost，与"一用例一库"对齐。启动时和库名一起设：
  ```powershell
  $env:MYSQL_DATABASE = "xinghe_stage_e_v3"
  $env:SPRING_RABBITMQ_VIRTUAL_HOST = "xinghe_stage_e_v3"
  ```
  **不设它就会连到 `/`**，那里可能残留着旧阶段声明的同名队列（见下条），启动会直接失败。
- 启动前先看 broker 现状（管理插件在 15672）：
  ```powershell
  $b64 = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("guest:guest"))
  $h = @{ Authorization = "Basic $b64" }
  (Invoke-RestMethod -Uri "http://localhost:15672/api/vhosts" -Headers $h) | ForEach-Object { $_.name }
  (Invoke-RestMethod -Uri "http://localhost:15672/api/queues" -Headers $h) |
    ForEach-Object { "{0,-30} vhost={1,-20} msgs={2} args={3}" -f $_.name, $_.vhost, $_.messages, ($_.arguments | ConvertTo-Json -Compress) }
  ```

**队列拓扑变更规则（硬规则，改 `MessagingConfig` 前必读）**

- **RabbitMQ 的队列参数不可变。** 队列已存在时，重新声明必须**参数完全一致**，否则 broker 拒绝并关闭 channel：
  ```
  PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-exchange' for queue 'xinghe.order.cancel' in vhost '/'
  reply-code=406
  ```
  应用侧表现为启动 **BUILD FAILURE**，堆栈里是 `com.rabbitmq.client.ShutdownSignalException`。**这不是代码 bug**，是 broker 侧的旧拓扑残留。
- 因此**改了 DLX / TTL / 重试队列拓扑后，必须在 broker 侧同步处理**，三选一：
  1. **换 vhost（首选，非破坏性）**：指到一个干净的 vhost，应用会把整套拓扑重新声明一遍；
  2. **删除同名旧队列**（先确认 `msgs=0` 且 `consumers=0`）：管理界面 http://localhost:15672 → Queues → 选对 vhost → Delete，或
     ```powershell
     # %2F 代表 vhost "/"
     Invoke-RestMethod -Method Delete -Headers $h -Uri "http://localhost:15672/api/queues/%2F/xinghe.order.cancel"
     ```
  3. **给队列改名做版本化**（如 `xinghe.order.cancel.v2`），旧队列留着不影响新代码。
- **禁止**：把代码参数改回旧值来"绕过"406；也不要无视它硬跑——拓扑没声明成功，后续消息路由全是错的。
- 删除前必须确认：队列名以 `xinghe.` 开头、vhost 是本次用例的 vhost。若 `msgs > 0`，先 peek 确认这批消息可以丢弃（`ackmode=reject_requeue_true`，只读不消费）。

**本项目的已知实例（2026-10-09）**

| vhost | 队列 | 参数 | 说明 |
|---|---|---|---|
| `/` | `xinghe.order.cancel` | `{}` | 旧代码声明，**声明新拓扑时会 406**。2026-10-09 已按方案 B 删除；**若它再次出现**，说明又有一次用旧代码的启动把 vhost 落到了 `/` |
| `/` | `xinghe.order.timeout` | 与 `MessagingConfig` 一致 | 参数本来就是对的，**不要删** |
| `xinghe_stage_e_v3` | 全套 8 条（含 `retry.1` / `retry.2`） | 与当前 `MessagingConfig` 一致 ✅ | 阶段 E 应该用它（配 `SPRING_RABBITMQ_VIRTUAL_HOST`）；其中 `xinghe.business.dead` 曾留有 3 条死信 |

---

## 3. 数据库约定

### 3.1 一用例一库，严禁越界

| 库 | 用途 | 是否可写 |
|---|---|---|
| `xinghe_trade` | 常规开发库 | ❌ 不要用于阶段 D 压测/重置 |
| `xinghe_stage_d` | D-02 库存并发扣减 | ✅ 仅限该用例 |
| `xinghe_stage_d_d03` | D-03 同 Token 并发支付 | ✅ 仅限该用例 |
| `xinghe_stage_d_d04` | D-04 不同 Token 并发支付 | ✅ 仅限该用例 |

`SHOW DATABASES` 里的其它库（`his`、`hmdp`、`sky_take_out`、`aynu` …）**属于其他项目，绝对不要碰**。

> **红线补充**：禁止在任何非本用例库上执行 `DELETE` / `UPDATE` / `DROP` / `TRUNCATE` / `-ResetDatabase`。
> 「把带 `COMMIT` 的重置语句包进事务里试一下」这种做法**已经造成过一次误删**（后来靠 binlog 才还原），不要再做。执行任何破坏性语句前，先 `SELECT` 确认目标库和目标行。

### 3.2 换库流程（红线第 3 条的展开）

1. **建库**（仅首次需要）：
   ```sql
   CREATE DATABASE IF NOT EXISTS xinghe_stage_d_d04 DEFAULT CHARACTER SET utf8mb4;
   ```
2. **让应用连上去并重启**：Spring Boot 中 **操作系统环境变量优先于 `.env`**，且必须在**启动应用的同一个窗口**里设置：
   ```powershell
   $env:MYSQL_DATABASE = "xinghe_stage_d_d04"
   cd "D:\codex-project\Xinghe Impression City Service Center\hd"
   mvn -s maven-settings.xml spring-boot:run
   ```
   忘记设就会掉回 `.env` 里的 `xinghe_trade`，测试数据会写进别的库。
3. **迁移 + 数据准备**（见 3.3）。两者缺一不可。

### 3.3 迁移是自动的，数据准备是手工的（最常漏）

**迁移**：应用启动时 Flyway 自动执行 `hd/src/main/resources/db/migration/V1__baseline_schema.sql`、`V2__stage_d_concurrency.sql`。空库也会全量建表（`baseline-on-migrate` 只在"库非空但没有版本表"时生效，不会跳过 V1）。

验证：
```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;  -- 期望 V1 / V2 都 success=1
```

**数据准备**：`store` 和测试 SKU **不会自动创建**，必须手工做。漏了就会看到「商品不存在或已下架」「购物车为空」。

1）插店铺（**先查再插**，重复插会报 `1062 - Duplicate entry '1' for key 'store.PRIMARY'`）：
```sql
SELECT * FROM store WHERE id = 1;
INSERT INTO store (id, name, status, created_at, updated_at)
VALUES (1, 'Stage D Test Store', 'OPEN', NOW(), NOW());
```
店铺必须存在且 `status='OPEN'`，否则建商品接口返回「店铺不存在或未营业」（见 `ProductManagementService#requireStore`）。

2）建测试 SKU 990001：
```powershell
$body = @{
  skuId = 990001; name = "Stage D Concurrency Test"
  description = "Dedicated Stage D test SKU"
  price = 1.00; availableQuantity = 10
} | ConvertTo-Json -Compress

$p = Invoke-WebRequest -Method Post -Uri "http://localhost:8081/api/store/products" `
  -Headers @{ "X-Store-Id" = "1" } -ContentType "application/json" -Body $body -SkipHttpErrorCheck
$p.StatusCode   # 期望 200
```
该接口同时创建 `product` 和 `product_inventory` 两行。

### 3.4 确认应用连的是哪个库（每次换库后必做）

没有健康检查接口。可靠做法：按 3.3 建好商品后**去目标库查**：
```sql
SELECT id, name, status FROM product WHERE id = 990001;   -- 查不到 = 应用连的不是这个库
```

### 3.5 已知的环境数据（不要当成脏数据清掉）

- `xinghe_stage_d` 的 `store.id = 1` 是手工插入的「**星河旗舰店**」，**代码里搜不到**，不是种子数据，不要覆盖或删除。
- 若 `XINGHE_SEED_ENABLED=true`，`DemoDataSeeder` 在**每次应用启动**都会补齐：商品 10001（星河精选运动鞋 / 199.00 / 库存 20）、演示订单 `XH2026100300000001`（`user-1001`，状态 `PAID`）、对应明细与物流记录。缺失会自动重建，属正常现象。

---

## 4. 测试与判定约定

### 4.1 脚本

| 脚本 | 用途 | 关键参数 |
|---|---|---|
| `scripts/stage-d-concurrency.ps1` | D-02 库存并发扣减 | `-ResetDatabase -DbName -SkuId -UserId -Requests -Rounds -InitialStock -StoreUsername -StorePassword -CustomerPassword -OutputDirectory` |
| `scripts/stage-d-payment-race.ps1` | D-03 / D-04 并发支付 | `-OrderNo`(父单号) `-UserId -LoginPassword -TokenMode same-token\|different-token -Requests -Rounds -OutputDirectory` |

两者都**必须用 PowerShell 7** 运行。

### 4.2 判定依据是数据库，不是脚本 summary

脚本字段口径（务必理解，否则会误判）：

- `Successful` = HTTP 状态码**恰好等于 200** 的请求数；
- `Failed` = `Requests - Successful`，**500 也会被算进来**，所以必须回看原始响应，区分「业务拦截（400/409）」和「系统缺陷（5xx）」；
- `DuplicatePayments` = `Max(0, 成功数 - 1)`，**是推断值，会假阳性**：同 Token 的幂等重放按设计也返回 200（`OrderService#payWithDatabaseToken` 命中 `SUCCEEDED` 后正常返回），20 个请求全部 200、真实状态迁移却只有 1 次。

因此每个用例都必须留**数据库证据**：订单状态分布、幂等表行数、库存锁定/售出数。

### 4.3 造一笔待支付订单（D-03 / D-04 通用）

```powershell
$user    = "stage-d-same-token"     # D-04 用 stage-d-different-token；必须与后续支付请求的 -UserId 一致
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-RestMethod -WebSession $session -Uri "http://localhost:8081/api/auth/csrf"
$csrfToken = $session.Cookies.GetCookies("http://localhost:8081")['XSRF-TOKEN'].Value
$headers = @{ "X-XSRF-TOKEN" = $csrfToken }
Invoke-RestMethod -Method Post -WebSession $session -Uri "http://localhost:8081/api/auth/register" -Headers $headers `
  -ContentType "application/json" -Body (@{ username = $user; password = $env:XINGHE_TEST_PASSWORD } | ConvertTo-Json -Compress)
Invoke-RestMethod -Method Post -WebSession $session -Uri "http://localhost:8081/api/auth/login" -Headers $headers `
  -ContentType "application/json" -Body (@{ username = $user; password = $env:XINGHE_TEST_PASSWORD } | ConvertTo-Json -Compress)

Invoke-WebRequest -Method Post -Uri "http://localhost:8081/api/cart/items" -Headers $headers `
  -WebSession $session -ContentType "application/json" -Body '{"skuId":990001,"quantity":1}' -SkipHttpErrorCheck

$checkout = Invoke-WebRequest -Method Post -Uri "http://localhost:8081/api/orders/checkout" `
  -WebSession $session -Headers ($headers + @{ "Idempotency-Token" = "prepare-$user" }) -SkipHttpErrorCheck
$orderNo = ($checkout.Content | ConvertFrom-Json).data[0].orderNo   # data[0] 一定是父单
```

- 支付必须传**父单号**；传子单号会得到 409「请支付父单」。
- 结算失败会清掉 Redis 幂等键，token 可以复用；但每次换新 token 更保险。

### 4.4 结果与报告

- 输出目录**按用例分开**：`stage-d-results\stage-d-d03\`、`stage-d-results\stage-d-d04\`（不要都写进 `stage-d`）。
- 每个用例交付：`summary.json`、`round-N-raw.json`、数据库证据（SQL 输出原文）、结论表：

| 用例 | 期望 | 实测(HTTP) | 实测(数据库) | 结论 | 证据文件 |
|---|---|---|---|---|---|

**通过与否由人确认**，代理只负责给证据和初步判读。

### 4.5 版本记录

结果中的被测版本应为 commit SHA，且必须在仓库目录下取：
```powershell
git -C "D:\codex-project\Xinghe Impression City Service Center" rev-parse HEAD
```
（脚本目前在启动目录执行该命令，常得到 `null`，属已知待修问题。）

---

## 5. 修改代码的约定

- **测试期间不要改业务代码。** 若必须改，先提交并记录 SHA，并在报告里注明「被测版本 + 改动内容」。否则「测的是哪个版本」说不清。
- 不要提交 `.env`、`stage-d-results/`、`.codex-*` 等临时产物。

---

## 6. 常用命令速查

```powershell
# 引擎自查
$PSVersionTable.PSVersion

# 数据库（只读）
$env:MYSQL_PWD = "<数据库密码>"
mysql -h localhost -P 3306 -u root --database=xinghe_stage_d_d04 --table `
  -e "SELECT order_no, parent_order_no, status FROM trade_order;"

# 启动后端（切库）
$env:MYSQL_DATABASE = "xinghe_stage_d_d04"
cd "D:\codex-project\Xinghe Impression City Service Center\hd"; mvn -s maven-settings.xml spring-boot:run

# 跑用例（先在目标专用库启动应用，并配置已播种的店员账号；脚本会为 D-02 每个请求注册独立顾客）
& "$root\scripts\stage-d-payment-race.ps1" -BaseUrl "http://localhost:8081" -UserId $user `
  -OrderNo $orderNo -LoginPassword $env:XINGHE_TEST_PASSWORD -Requests 20 -Rounds 1 -TokenMode "different-token" `
  -OutputDirectory "$root\stage-d-results\stage-d-d04"
```

---

## 7. 已知问题清单（写代码 / 写文档时一并修掉）

1. 两个阶段 D 脚本首行均有 `#Requires -Version 7`，并通过真实 `/api/auth/csrf` + `/api/auth/login` 会话调用接口；脚本不再发送 `X-User-Id` / `X-Store-Id`。D-02 需要 `XINGHE_STAGE_D_EMPLOYEE_USERNAME/PASSWORD`（或显式参数）和 `XINGHE_TEST_PASSWORD`。
2. 脚本 metadata 使用仓库绝对定位 `git -C $PSScriptRoot\.. rev-parse HEAD`，不会因启动目录错误得到 `null`。
3. `summary.json` 的 `DuplicatePayments` 口径会假阳性（见 4.2），报告里必须注明。
4. `product` 表主键叫 `id`，**没有 `sku_id` 列**（`trade_order_item`、`product_inventory` 才有 `sku_id`）。查商品要用 `WHERE id = 990001`。
5. 文档模板里的 `-OutputDirectory "$root\stage-d-results\stage-d"` 是笔误，应指向对应用例目录。
6. `spring.rabbitmq.virtual-host` 未在 `application.yml` 里配置，`.env.example` 和启动文档也没提 vhost（见 2.2）→ 跑阶段 E 时会连到 `/` 并撞上旧队列导致启动 BUILD FAILURE。建议在 `.env.example` 和启动步骤里显式写上 `SPRING_RABBITMQ_VIRTUAL_HOST`。
