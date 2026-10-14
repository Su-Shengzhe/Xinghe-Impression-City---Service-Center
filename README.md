# 星河印象城交易服务中心

基于 Spring Boot 3 和 Vue 3 的交易与客服系统。下面的 Docker 步骤适合把项目交给另一台 Windows 电脑使用；开发和测试说明保留在后半部分。

## Docker 快速部署（接收方从这里开始）

收到的离线包目录应包含 `images.tar`、`compose.yaml`、`README.md`、`requirements.txt`、`SHA256SUMS.txt`、`VERSION.txt` 和 `deploy/`。解压后，在 **包含 `compose.yaml` 的目录**打开 PowerShell 7，以下命令都从该目录执行。目标电脑只需安装并启动 Docker Desktop（选择 Linux containers 模式）和 PowerShell 7；镜像已在 `images.tar` 中，不需要单独安装 Java、Node.js、MySQL 或 Redis。详细依赖见 [`requirements.txt`](requirements.txt)。

### 1. 配置本机密码

```powershell
Copy-Item .\deploy\docker.env.example .\deploy\docker.env
notepad .\deploy\docker.env
```

把文件中每个以 `CHANGE_ME` 开头的示例值换成自己的强密码，至少包括 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`RABBITMQ_PASSWORD` 和 `XINGHE_AUTH_EMPLOYEE_SEED_PASSWORD`。保留 `XINGHE_VERSION=1.0.0`，使它与包内镜像标签一致。若电脑的 8080 端口已占用，可修改 `WEB_PORT`。`deploy/docker.env` 只保存在部署电脑，不要放进压缩包或提交到 Git。

### 2. 导入镜像并启动

```powershell
pwsh -File .\deploy\import-and-start.ps1
docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps
Invoke-RestMethod http://localhost:8081/actuator/health
```

等待 `ps` 显示各容器正常运行、健康检查返回 `UP`。浏览器打开 `http://localhost:8080`；从局域网其他设备访问时使用 `http://部署电脑的IP:8080`，并确认 Windows 防火墙允许该端口。若修改了 `WEB_PORT`，把地址中的 8080 换成新端口。

### 3. 首次创建店铺和员工

后端启动时 Flyway 自动创建数据库表，但不会创建营业店铺。**仅对新部署的自有数据库**执行一次：

```powershell
pwsh -File .\deploy\initialize-store.ps1 -StoreId 1 -StoreName '星河印象城店铺'
```

打开 `deploy/docker.env`，确认 `XINGHE_AUTH_EMPLOYEE_SEED_USERNAME` 是想要的员工用户名、`XINGHE_AUTH_EMPLOYEE_SEED_PASSWORD` 是刚设置的密码，`XINGHE_AUTH_EMPLOYEE_SEED_STORE_ID=1`，然后按顺序操作：

1. 将 `XINGHE_AUTH_EMPLOYEE_SEED_ENABLED=false` 改成 `true`。
2. 执行 `docker compose --env-file .\deploy\docker.env -f .\compose.yaml up -d --no-build --pull never --force-recreate backend`。
3. 待后端恢复健康后，用员工账号登录页面。
4. 立即把 `XINGHE_AUTH_EMPLOYEE_SEED_ENABLED` 改回 `false`，再执行一次第 2 步的命令。已有员工账号不会被覆盖；保持播种开关为 `true` 会使后续启动失败。

员工登录后可以创建商品。顾客可直接在页面注册账号。演示数据播种和 Elasticsearch 在这套 Compose 配置中默认关闭，RabbitMQ 消息链路默认启用。

### 4. 日常使用与排障

```powershell
# 查看状态和后端日志
docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps
docker compose --env-file .\deploy\docker.env -f .\compose.yaml logs --tail 200 backend

# 停止和再次启动；保留 MySQL、Redis、RabbitMQ 数据卷
docker compose --env-file .\deploy\docker.env -f .\compose.yaml down
docker compose --env-file .\deploy\docker.env -f .\compose.yaml up -d --no-build --pull never
```

不要运行 `docker compose down -v`，它会删除数据库等持久化卷。启动失败时先看 `ps` 和 `logs`；首次初始化后再次启动失败，还要检查员工播种开关是否已改回 `false`。外网部署需要在前端配置 HTTPS。更多操作与限制见 [`deploy/DEPLOYMENT.md`](deploy/DEPLOYMENT.md)。

## 从源码构建或重新制作离线包

需要源码构建时，先安装 Docker Desktop 和 PowerShell 7，并确认可访问 Docker Hub、Maven 和 npm 仓库。在**项目根目录**执行：

```powershell
Copy-Item .\deploy\docker.env.example .\deploy\docker.env
notepad .\deploy\docker.env # 替换所有示例密码
docker compose --env-file .\deploy\docker.env -f .\compose.yaml -f .\compose.build.yaml up -d --build
```

首次初始化仍按上面的第 3 步操作。要把镜像带到其他电脑，可在项目根目录运行：

```powershell
pwsh -File .\deploy\build-offline-bundle.ps1
```

完成后将 `docker-release\xinghe-trade-center\` 整个目录压缩并发送。构建脚本会一并放入 README、依赖清单、启动脚本、镜像归档、版本和校验值。Docker 镜像拉取遇到 `403`/`EOF` 时检查 Docker Engine 中的镜像源；直连 Docker Hub 超时时，在 Docker Desktop 的 Proxies 设置中为镜像拉取配置可用的 HTTPS 代理。

## 第一阶段：客服基础流程

已实现客服后端基础闭环，当前采用“固定流程优先”的路由方式，为后续接入 Agent Tool Calling 预留统一服务接口：

- 客服会话和消息写入 MySQL
- Spring Security 登录会话通过 Redis Session Cookie 校验用户身份和会话归属；不信任 `X-User-Id` / `X-Store-Id`
- 订单查询只允许访问当前用户自己的订单
- FAQ 固定问答：退款、配送、支付、人工客服
- Redis 保存每个会话最近 20 条上下文，30 分钟自动过期
- SSE 格式客服消息接口（当前返回单条完整回复）
- 商品查询接口和物流查询接口
- 商品和客服规则后续可迁移到 Elasticsearch 知识索引

## 第二阶段：受控 Agent 客服

- 可选接入 OpenAI-compatible Chat Completions；默认关闭，未启用或请求失败时继续走固定 FAQ/规则路由
- Agent 仅能调用五个只读工具：`query_order_detail`、`query_logistics`、`search_products`、`get_service_policy`、`get_after_sale_policy`
- 订单和物流工具在服务端使用当前登录会话校验归属，不接受模型传入用户身份
- 订单号、商品关键词和无参数规则工具均执行严格参数校验；模型传入的 `userId` 只被忽略，不参与权限判断
- 限制每轮工具调用数量；未知工具、超时、非法 JSON 或模型错误不会执行写操作，并会降级为现有客服流程
- 历史消息和工具结果标记为不可信数据，提示词注入不能改变工具白名单或用户权限；每次工具调用记录用户、工具、结果和耗时
- 模型 API 调用不占用数据库写事务
- 启用后，最近会话消息以及必要的查询结果会发送给所配置的模型服务商；默认关闭时不会发送对话内容

可通过环境变量配置（不要把 API Key 写进版本库）：

```powershell
$env:XINGHE_AGENT_ENABLED = "true"
$env:XINGHE_AGENT_API_KEY = "你的模型服务 API Key"
$env:XINGHE_AGENT_BASE_URL = "https://api.openai.com/v1"
$env:XINGHE_AGENT_MODEL = "gpt-4o-mini"
```

`XINGHE_AGENT_BASE_URL` 和 `XINGHE_AGENT_MODEL` 可按所用的 OpenAI-compatible 服务商调整。停用时将 `XINGHE_AGENT_ENABLED` 设为 `false` 或删除该变量。

阶段 B 验收测试覆盖：五工具白名单、正常订单和物流查询、伪造用户身份、未知工具、非法订单参数、售后规则查询、提示词注入隔离、非法 JSON、模型超时和工具调用日志。

```bash
cd hd
mvn -s maven-settings.xml test
```

## 初始化数据库

数据库由 Flyway 版本迁移唯一管理，应用启动不会再执行 `schema.sql`。新环境执行 `hd/src/main/resources/db/migration/` 下的 V1–V6；已有阶段 C 数据库在 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` 下会以版本 1 建基线并执行后续迁移。不要同时启用 `schema.sql` 和 Flyway。

阶段 D 的库存写操作使用 MySQL `product_inventory.version` 条件更新，库存总量由 `available_quantity + locked_quantity + sold_quantity` 校验。支付幂等记录保存在 `payment_idempotency`，Redis 不参与最终库存扣减或支付幂等。

阶段 D 默认测试与脚本：

```powershell
cd hd
mvn -s maven-settings.xml test
cd ..
.\scripts\stage-d-concurrency.ps1 -ResetDatabase -DbName xinghe_stage_d -SkuId 990001 `
  -StoreUsername $env:XINGHE_STAGE_D_EMPLOYEE_USERNAME -StorePassword $env:XINGHE_STAGE_D_EMPLOYEE_PASSWORD `
  -CustomerPassword $env:XINGHE_TEST_PASSWORD
.\scripts\stage-d-payment-race.ps1 -OrderNo <pending-parent-order> -UserId stage-d-same-token `
  -LoginPassword $env:XINGHE_TEST_PASSWORD -TokenMode same-token
.\scripts\stage-d-payment-race.ps1 -OrderNo <pending-parent-order> -UserId stage-d-different-token `
  -LoginPassword $env:XINGHE_TEST_PASSWORD -TokenMode different-token
```

并发脚本默认从 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME` 和 `MYSQL_PASSWORD` 读取数据库连接参数。`-ResetDatabase` 会删除测试订单和购物车，只应在专用测试数据库使用；每次运行会在 `stage-d-results` 保存原始响应、性能统计、提交版本和库存快照。

D-03/D-04 的 `-UserId` 必须是订单所属的已注册顾客账号。请先按“登录与会话”章节的 CSRF/Cookie 流程注册账号、登录并创建待支付父单；竞态脚本只负责为每个并发请求建立独立会话并执行支付，不会在支付窗口内注册账号。

开发环境默认可通过根目录 `.env` 中的 `XINGHE_SEED_ENABLED=true` 写入演示数据：

- 演示用户：`user-1001`
- 演示订单：`XH2026100300000001`
- 演示商品：`10001`
- 演示物流单号：`XH-DEMO-TRACK-001`

关闭演示数据时，将 `XINGHE_SEED_ENABLED` 设置为 `false`。

## 启动依赖

本地启动 MySQL（创建 `xinghe_trade`）、Redis、RabbitMQ 和 Elasticsearch 后运行：

```bash
cd hd
mvn -s maven-settings.xml spring-boot:run
```

本地未启动 RabbitMQ 时，应用默认不会启动订单超时消费者，因此不会反复打印连接失败日志。需要启用 RabbitMQ 阶段 E 消息功能时，必须先启动 RabbitMQ，并在**同一个启动终端**明确设置与隔离数据库同名的 vhost；不要省略此项，否则会连接默认 `/` vhost：

```powershell
$env:MYSQL_DATABASE = "xinghe_stage_e"
$env:SPRING_RABBITMQ_VIRTUAL_HOST = "xinghe_stage_e"
$env:XINGHE_MESSAGING_ENABLED = "true"
$env:XINGHE_ELASTICSEARCH_ENABLED = "true" # 仅在 ES 已运行且安装 IK 插件时启用
cd hd
mvn -s maven-settings.xml spring-boot:run
```

验收环境须使用专用数据库和同名 RabbitMQ vhost（例如 `xinghe_stage_e`），先确认该 vhost/队列为空或已知，再启动应用。不可对 `xinghe_trade` 或其他用例库执行清理/重置。首次换库时先确认 Flyway V1–V6 均成功，并在目标库准备营业店铺和测试商品；应用创建商品接口应返回成功后，再进行消息场景验收。记录被测 commit、数据库/vhost、环境开关、HTTP 响应、`message_outbox`/`message_processing` 查询结果以及 RabbitMQ 队列参数/消息数。

阶段 E 的可靠消息链路使用 `message_outbox` 和 `message_processing` 两张表：订单超时与商品索引事件先和业务事务一起写入 Outbox，再由后台发布器以持久消息和 publisher confirm 发布。消费者按 `message_id` 幂等处理；失败消息等待 5 秒、30 秒后分别进入第二、第三次消费，仍失败才进入 `xinghe.business.dead`。发布器自身失败达到 10 次时在 Outbox 标记为 `DEAD`。应用重启会回收滞留的 `PUBLISHING` 事件。此次重试队列的 DLX 参数已变化，已有 vhost 中旧队列不能直接重新声明；请按 `AGENTS.md` 使用干净 vhost，勿清理有消息的旧队列。

订单同时持久化 `expires_at`：购物车结算的父、子订单支付期限为 15 分钟，旧单笔下单为 30 秒；到期订单不可再支付。无论 RabbitMQ 是否启用，后台每 30 秒扫描到期的待支付子订单和单笔订单，并在事务中释放锁定库存；可用 `XINGHE_ORDER_EXPIRY_SCAN_MS` 调整扫描间隔。V5 迁移给升级时已有的待支付订单留 15 分钟宽限期。购物车结算幂等 Token 由 MySQL `(user_id, token)` 唯一约束持久化，并与父子订单同事务提交，重复请求返回已提交订单。

商品查询默认仍走 MySQL。启用 ES 投影和查询降级时设置：

```powershell
$env:XINGHE_ELASTICSEARCH_ENABLED = "true"
```

商品管理的创建、资料更新和上下架会在同一事务写入 `product.index` 事件；ES 不可用时客服商品查询自动回退 MySQL。商品索引文档使用 `ik_max_word`/`ik_smart` 分词器，因此 ES 必须安装 IK 插件才能创建索引。完整索引重建仅限维护窗口：

```http
POST /api/store/products/rebuild-index
```

该接口只有同时设置 `XINGHE_ELASTICSEARCH_ENABLED=true` 和 `XINGHE_INDEX_REBUILD_API_ENABLED=true` 才注册，默认不可调用。当前实现会删除在线索引后从 MySQL 全量写入商品，期间搜索会回退 MySQL；请仅在隔离维护窗口启用，不要在常规运行环境开放给店铺员工。上线前若需要无中断重建，还需实现版本化索引与别名原子切换。

## 登录与会话

前端使用 `/api/auth/me` 在启动时恢复服务端登录状态。顾客通过注册/登录进入客服和交易工作台；店铺员工登录后进入员工工作台，所属店铺来自账号关联。退出只注销当前会话。账号禁用、角色、所属店铺、用户名或密码散列发生变化后，下一次受保护请求将使旧会话失效并返回 401；这会为每次认证请求增加一次按账号主键查询。所有写请求使用 `XSRF-TOKEN` Cookie 对应的 `X-XSRF-TOKEN` 请求头；浏览器前端自动维护 Cookie 和 CSRF token。不要在业务请求中传 `X-User-Id` 或 `X-Store-Id`，这两个请求头不是身份凭证。

手动 HTTP 联调先获取 CSRF cookie/token，再登录并沿用同一 Cookie jar：

```powershell
$session = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$csrf = Invoke-RestMethod -WebSession $session -Uri 'http://localhost:8081/api/auth/csrf'
$token = $session.Cookies.GetCookies('http://localhost:8081')['XSRF-TOKEN'].Value
$headers = @{ 'X-XSRF-TOKEN' = $token }
Invoke-RestMethod -Method Post -WebSession $session -Uri 'http://localhost:8081/api/auth/login' `
  -Headers $headers -ContentType 'application/json' `
  -Body (@{ username = 'your-customer-account'; password = $env:XINGHE_TEST_PASSWORD } | ConvertTo-Json -Compress)
Invoke-RestMethod -WebSession $session -Uri 'http://localhost:8081/api/auth/me'
```

CSRF token 可从 `/api/auth/csrf` 的 `data` 字段或 `XSRF-TOKEN` Cookie 读取。写请求（包括客服 SSE POST）需在同一会话中同时发送 Cookie 与 `X-XSRF-TOKEN`。匿名商品查询仍允许公开访问。客服会话列表目前只保存在浏览器本地，消息数据仍由后端校验当前会话所有权。

店铺员工账号由受信任的服务端播种流程建立，不能通过顾客注册取得员工角色。脚本与服务端集成测试必须使用真实登录会话，不能以任意身份请求头模拟用户。

## 前端联调

前端位于 `qd`，后端位于 `hd`。前端需要 Node.js 18 或更高版本。先启动后端及所需依赖，再在 `qd` 目录运行：

```bash
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。Vite 会将 `/api` 请求代理到 `http://localhost:8081`，无需额外配置跨域。使用已播种员工账号或注册顾客账号登录；页面不会预置或让用户编辑业务身份 ID。客服会话记录列表暂存在当前浏览器中，后端目前没有会话列表接口。

店铺商品 `GET /api/store/products` 与店铺订单 `GET /api/orders/store` 已改为分页响应 `data: { items, page, size, hasMore }`，默认每页 50、最多 100 条；旧客户端若按数组读取响应，发布时必须同步升级。客服消息接口每次返回最近 100 条（按时间正序），使用 `before=<最早消息的 messageId>` 继续读取；商品库存按页批量查询，避免逐商品读取。V6 为这些列表添加排序索引，并将客服消息时间精度提高到微秒；对存量大表需在维护窗口评估迁移耗时。

## 上线前交付检查

- CI 在每次推送和拉取请求上执行后端测试及前端锁定依赖构建；本地可执行 `mvn -s hd/maven-settings.xml -f hd/pom.xml test`，以及在 `qd` 目录执行 `npm ci`、`npm run build`。
- 在专用预发布库验证 Flyway V1–V6 均成功；预发布 RabbitMQ vhost 与库名隔离。不要对生产或共享开发库运行重置脚本。
- 关闭演示播种、员工播种和索引重建接口，确认 `.env` 中无默认密码；确认 Redis、MySQL 连通及 `GET /actuator/health` 为 `UP`，只暴露健康状态，不暴露详细组件信息。
- 人工冒烟覆盖注册/登录、账号禁用与角色/店铺变更后的 401、退出失败保留本地状态、会话历史翻页、购物车结算/超时、消息重试和发货。并发与数据库结论按 `AGENTS.md` 留证据，记录 commit、参数、专用库与 vhost。
- 发布前备份数据库并准备应用回滚版本。V6 会调整客服消息时间精度并创建索引，须在专用预发布库验证执行耗时；回滚应用代码不应删除已执行的 Flyway 迁移。

