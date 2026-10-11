# 阶段 E 执行记录

## 执行结论

本次完成阶段 E 的实现审查、RabbitMQ vhost 隔离配置补充、自动化测试和前端构建检查。**阶段 E 在线验收未完成**：当前 RabbitMQ、RabbitMQ 管理端和 Elasticsearch 均不可达，Docker Desktop daemon 未运行；未启动消息消费者、未发布/消费测试消息、未创建/修改 ES 索引。因此不得将 RabbitMQ 重试/死信、订单超时库存释放或 ES 同步标记为通过。

## 版本与安全边界

- 执行日期：2026-10-10
- 测试时基础 HEAD：`39f3300e6ba1b1bca4632d961d710693d662bc92`。执行时工作区有未提交改动（包含阶段 F 业务代码）；测试结果对应当时工作树，不能只用此 SHA 代表完整被测内容。
- 数据库：只读枚举确认 `xinghe_stage_e` 不存在；存在 `xinghe_trade`、`xinghe_stage_e_v3`、`xinghe_stage_f`。没有向任何数据库写入，也没有启动应用连库。
- RabbitMQ vhost/队列：未能查询，管理端 `localhost:15672` 不可达；未清理、删除或声明任何队列。
- 未提交工作区已有阶段 F 代码、文档和运行证据改动；本次另修改 `.env.example`、`README.md`、`hd/src/main/resources/application.yml`，并新增本记录。没有提交代码或变更已有阶段 F 结果。最终验收需先整理并记录实际被测 diff/commit。

## 阶段 E 范围审查

当前 HEAD 已包含以下实现：

- Flyway V3 创建 `message_outbox`、`message_processing`。
- 订单超时和商品索引事件写入 Outbox；定时发布器发送持久消息并使用 publisher confirm/mandatory return，发布失败退避重试，达到 10 次后标记 `DEAD`。
- RabbitMQ 拓扑配置订单超时延迟队列、订单取消队列、商品索引队列、两级 TTL 重试队列及业务死信队列。
- 超时消费者按消息 ID 幂等处理未支付订单，并释放锁定库存；商品消费者在启用 ES 时幂等更新索引。
- ES 搜索异常时商品搜索回退 MySQL；提供商品索引重建端点。商品文档使用 IK 分词器，需确认 ES 安装 IK 插件。

## 本次变更

- `hd/src/main/resources/application.yml`：显式读取 `SPRING_RABBITMQ_VIRTUAL_HOST`，避免误连默认 `/` vhost。
- `.env.example`：增加阶段 E 隔离 vhost 示例和 Elasticsearch 开关。
- `README.md`：补充专用库/同名 vhost、安全启动及阶段 E 证据要求。

启动时需在同一 PowerShell 终端将 `MYSQL_DATABASE` 和 `SPRING_RABBITMQ_VIRTUAL_HOST` 均设置为专用名称（例如 `xinghe_stage_e`），不可沿用 `.env` 默认的 `xinghe_trade`。启用 ES 前需确认服务可用且 IK 插件已安装。

## 自动化验证

| 检查 | 结果 |
|---|---|
| `mvn -s maven-settings.xml test`（`hd`） | 通过：47 项，0 失败/错误/跳过 |
| `npm run build`（`qd`） | 通过：Vite 生产构建成功 |
| `git diff --check` | 通过（退出码 0）；Git 提示现有若干工作区文件下次触及时可能进行 LF/CRLF 转换 |
| RabbitMQ `localhost:5672` | 不可达 |
| RabbitMQ 管理端 `localhost:15672` | 不可达 |
| Elasticsearch `localhost:9200` | 不可达 |
| Docker Desktop daemon | 未运行，无法启动依赖容器 |

## 在线验收待办（不可据本记录宣称通过）

1. 启动隔离 RabbitMQ，创建专用 vhost `xinghe_stage_e`，记录队列名、参数、消费者、消息数。
2. 在专用库 `xinghe_stage_e` 运行 Flyway V1–V3，准备测试店铺和商品；确认连接目标库后再进行任何业务写入。
3. 使用 `XINGHE_MESSAGING_ENABLED=true`、`SPRING_RABBITMQ_VIRTUAL_HOST=xinghe_stage_e` 启动应用，验证创建商品及修改商品时 Outbox 状态从 `PENDING` 到 `PUBLISHED`，并观察消息幂等记录。
4. 创建专用待支付订单，验证延迟到期后父/子订单状态、锁定/可售库存变化及 `message_processing` 记录；重复投递不得二次释放库存。
5. 注入可恢复的消费者失败，留存两级延迟队列和最终死信证据；不能向已存在但参数不匹配的队列声明拓扑。
6. 在安装 IK 的 Elasticsearch 上验证索引创建、商品变更异步同步、搜索建议/查询及索引重建；停止 ES 后验证 MySQL 查询回退。
7. 保存原始 HTTP、SQL 查询输出、RabbitMQ 队列快照、ES 请求/响应、结果摘要和最终被测 commit SHA。