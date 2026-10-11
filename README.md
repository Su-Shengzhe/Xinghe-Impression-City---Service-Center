# 星河印象城交易服务中心

基于 Spring Boot 3、MyBatis Plus、Redis、MySQL、RabbitMQ 和 Elasticsearch 的统一交易后台。

## 第一阶段：客服基础流程

已实现客服后端基础闭环，当前采用“固定流程优先”的路由方式，为后续接入 Agent Tool Calling 预留统一服务接口：

- 客服会话和消息写入 MySQL
- `X-User-Id` 请求头校验用户身份和会话归属
- 订单查询只允许访问当前用户自己的订单
- FAQ 固定问答：退款、配送、支付、人工客服
- Redis 保存每个会话最近 20 条上下文，30 分钟自动过期
- SSE 格式客服消息接口（当前返回单条完整回复）
- 商品查询接口和物流查询接口
- 商品和客服规则后续可迁移到 Elasticsearch 知识索引

## 第二阶段：受控 Agent 客服

- 可选接入 OpenAI-compatible Chat Completions；默认关闭，未启用或请求失败时继续走固定 FAQ/规则路由
- Agent 仅能调用五个只读工具：`query_order_detail`、`query_logistics`、`search_products`、`get_service_policy`、`get_after_sale_policy`
- 订单和物流工具在服务端使用当前 `X-User-Id` 校验归属，不接受模型传入用户身份
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

数据库由 Flyway 版本迁移唯一管理，应用启动不会再执行 `schema.sql`。新环境执行 `hd/src/main/resources/db/migration/V1__baseline_schema.sql` 和 `V2__stage_d_concurrency.sql`；已有阶段 C 数据库在 `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` 下会以版本 1 建基线后执行版本 2。不要同时启用 `schema.sql` 和 Flyway。

阶段 D 的库存写操作使用 MySQL `product_inventory.version` 条件更新，库存总量由 `available_quantity + locked_quantity + sold_quantity` 校验。支付幂等记录保存在 `payment_idempotency`，Redis 不参与最终库存扣减或支付幂等。

阶段 D 默认测试与脚本：

```powershell
cd hd
mvn -s maven-settings.xml test
cd ..
.\scripts\stage-d-concurrency.ps1 -ResetDatabase
.\scripts\stage-d-payment-race.ps1 -OrderNo <pending-parent-order> -TokenMode same-token
.\scripts\stage-d-payment-race.ps1 -OrderNo <pending-parent-order> -TokenMode different-token
```

并发脚本默认从 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USERNAME` 和 `MYSQL_PASSWORD` 读取数据库连接参数。`-ResetDatabase` 会删除测试订单和购物车，只应在专用测试数据库使用；每次运行会在 `stage-d-results` 保存原始响应、性能统计、提交版本和库存快照。

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

验收环境须使用专用数据库和同名 RabbitMQ vhost（例如 `xinghe_stage_e`），先确认该 vhost/队列为空或已知，再启动应用。不可对 `xinghe_trade` 或其他用例库执行清理/重置。首次换库时先确认 Flyway V1–V3 均成功，并在目标库准备营业店铺和测试商品；应用创建商品接口应返回成功后，再进行消息场景验收。记录被测 commit、数据库/vhost、环境开关、HTTP 响应、`message_outbox`/`message_processing` 查询结果以及 RabbitMQ 队列参数/消息数。

阶段 E 的可靠消息链路使用 `message_outbox` 和 `message_processing` 两张表：订单超时与商品索引事件先和业务事务一起写入 Outbox，再由后台发布器以持久消息和 publisher confirm 发布。消费者按 `message_id` 幂等处理；失败消息经过两级延迟重试后进入 `xinghe.business.dead`，发布器自身失败达到 10 次时在 Outbox 标记为 `DEAD`。应用重启会回收滞留的 `PUBLISHING` 事件。

商品查询默认仍走 MySQL。启用 ES 投影和查询降级时设置：

```powershell
$env:XINGHE_ELASTICSEARCH_ENABLED = "true"
```

商品管理的创建、资料更新和上下架会在同一事务写入 `product.index` 事件；ES 不可用时客服商品查询自动回退 MySQL。商品索引文档使用 `ik_max_word`/`ik_smart` 分词器，因此 ES 必须安装 IK 插件才能创建索引。索引可重复重建：

```http
POST /api/store/products/rebuild-index
```

该接口只在 `XINGHE_ELASTICSEARCH_ENABLED=true` 时注册，重建前删除旧索引，再从 MySQL 全量写入当前商品。

## 客服接口

创建会话：

```http
POST /api/customer-service/sessions
X-User-Id: user-1001
```

发送消息（SSE）：

```http
POST /api/customer-service/sessions/{sessionId}/messages
X-User-Id: user-1001
Content-Type: application/json

{"content":"请帮我查询订单 XH202610021234567890 的状态"}
```

查询订单和物流：

```http
GET /api/customer-service/orders/{orderNo}
GET /api/customer-service/orders/{orderNo}/logistics
X-User-Id: user-1001
```

阶段 A 可直接查询演示订单：

```text
XH2026100300000001
```

商品搜索：

```http
GET /api/customer-service/products?keyword=鞋
```

购物车（所有接口都使用 `X-User-Id` 隔离当前用户）：

```http
GET /api/cart

POST /api/cart/items
X-User-Id: user-1001
Content-Type: application/json

{"skuId":10001,"quantity":2}

PUT /api/cart/items/{skuId}
X-User-Id: user-1001
Content-Type: application/json

{"quantity":1}

DELETE /api/cart/items/{skuId}
X-User-Id: user-1001
```

添加购物车时由服务端校验商品存在且在售，价格从商品表读取；同一用户重复添加同一 SKU 会原子累加数量。

购物车结算：

```http
POST /api/orders/checkout
X-User-Id: user-1001
```

结算会返回一个父订单编号及按店铺拆分的子订单编号；父订单保存总金额，子订单保存店铺金额和订单商品价格快照。订单查询、取消及店铺发货接口如下：

```http
GET  /api/orders/{orderNo}              X-User-Id: user-1001
POST /api/orders/{orderNo}/cancel       X-User-Id: user-1001
POST /api/orders/{childOrderNo}/ship    X-Store-Id: 1
```

发货请求体包含 `company` 和 `trackingNo`。父单取消会取消所有待支付子单并释放其锁定库存；已支付子单发货后进入 `SHIPPED`，物流记录可通过客服物流查询接口查看。

店铺商品维护接口使用 `X-Store-Id` 做联调期归属检查：

```http
POST /api/store/products
PUT  /api/store/products/{skuId}
PUT  /api/store/products/{skuId}/status
PUT  /api/store/products/{skuId}/inventory
```

创建请求需要 `skuId`、`name`、`price`、`availableQuantity`，编辑仅更新商品资料，上下架状态为 `ON_SALE` / `OFF_SALE`；库存接口只调整可用量，不覆盖待支付订单锁定的库存。

结算不接收客户端价格或用户 ID。服务端按当前商品价格和数据库可用库存创建待支付订单，成功后清空该用户购物车；一次结算为每个 SKU 创建一笔订单，返回订单号与状态列表。启用 RabbitMQ 订单超时处理后，未支付订单超时会释放数据库锁定库存。

## 前端联调

前端位于 `qd`，后端位于 `hd`。前端需要 Node.js 18 或更高版本。先启动后端及所需依赖，再在 `qd` 目录运行：

```bash
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`。Vite 会将 `/api` 请求代理到 `http://localhost:8081`，无需额外配置跨域。页面默认使用联调用户 `user-1001`；客服会话记录列表暂存在当前浏览器中，后端目前没有会话列表接口。

