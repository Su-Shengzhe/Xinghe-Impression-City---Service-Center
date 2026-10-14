# 星河印象城 Docker 部署说明

这套部署包含前端 Nginx、Spring Boot、MySQL、Redis 和 RabbitMQ。Elasticsearch 默认关闭，因为项目索引需要安装 IK 分词插件；关闭时商品查询会回退 MySQL。

## 1. 要求

- 构建电脑：Docker Desktop，Linux container 模式，PowerShell 7，可访问镜像仓库和 Maven/npm 仓库。
- 目标电脑：Docker Desktop，Linux container 模式，PowerShell 7。使用 `images.tar` 时不需要访问镜像仓库。
- 离线包和目标电脑应使用兼容的 CPU 架构。普通 Windows Intel/AMD 电脑通常都是 `linux/amd64`。
- 建议至少 4 CPU、8 GB 内存和 10 GB 可用磁盘。

## 2. 在开发电脑构建离线包

在仓库根目录执行：

```powershell
pwsh -File .\deploy\build-offline-bundle.ps1
```

输出目录默认为 `docker-release\xinghe-trade-center\`，其中包括 `images.tar`、Compose 文件、校验值和部署脚本。发送整个目录，不要把本机的 `.env` 或含真实密码的 `deploy/docker.env` 一起发送。

如果目标电脑可以访问互联网，也可以只发送项目源码，在目标电脑执行：

```powershell
Copy-Item .\deploy\docker.env.example .\deploy\docker.env
# 编辑 deploy/docker.env，替换全部 CHANGE_ME
docker compose --env-file .\deploy\docker.env -f .\compose.yaml -f .\compose.build.yaml up -d --build
```

## 3. 在目标电脑导入并启动

解压/复制离线目录后，在该目录执行：

```powershell
Copy-Item .\deploy\docker.env.example .\deploy\docker.env
# 用文本编辑器修改 deploy/docker.env，替换每个 CHANGE_ME；不要复用示例密码。
pwsh -File .\deploy\import-and-start.ps1
```

查看状态与日志：

```powershell
docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps
docker compose --env-file .\deploy\docker.env -f .\compose.yaml logs --tail 200 backend
```

默认入口：

- 业务页面：`http://目标电脑IP:8080`
- 后端健康检查（仅目标电脑本机）：`http://localhost:8081/actuator/health`
- RabbitMQ 管理页（仅目标电脑本机）：`http://localhost:15672`

## 4. 新库首次初始化店铺和员工

Flyway 会自动创建 V1–V6 表，但不会创建正式店铺。确认后端健康后执行一次：

```powershell
pwsh -File .\deploy\initialize-store.ps1 -StoreId 1 -StoreName '星河印象城店铺'
```

然后完成一次性员工账号播种：

1. 编辑 `deploy/docker.env`，设置唯一的员工用户名和至少 8 位的强密码，并把 `XINGHE_AUTH_EMPLOYEE_SEED_ENABLED` 改为 `true`。
2. 重建后端容器并观察其健康：

   ```powershell
   docker compose --env-file .\deploy\docker.env -f .\compose.yaml up -d --force-recreate backend
   docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps
   ```

3. 登录成功后，立刻把 `XINGHE_AUTH_EMPLOYEE_SEED_ENABLED` 改回 `false`，再执行一次同样的 `--force-recreate backend` 命令。该播种器拒绝覆盖已有账号；如果一直保持启用，后续重启会失败。

顾客账号直接在页面注册。不要启用 `XINGHE_SEED_ENABLED`，Compose 已固定关闭演示数据。

## 5. 日常命令

```powershell
# 状态
docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps

# 查看全部日志
docker compose --env-file .\deploy\docker.env -f .\compose.yaml logs -f --tail 200

# 停止，但保留数据库、Redis 和 RabbitMQ 数据卷
docker compose --env-file .\deploy\docker.env -f .\compose.yaml down

# 再次启动
docker compose --env-file .\deploy\docker.env -f .\compose.yaml up -d --no-build
```

不要执行 `docker compose down -v`，该参数会删除数据库等持久化卷。升级前先备份 MySQL，并保留上一版 `images.tar` 以便应用回滚。Flyway 迁移一旦执行，不应通过删除数据库表来回滚。

## 6. 上线注意事项

- 正式公网部署应在前端容器之前增加 HTTPS 反向代理，并验证 Session Cookie 的 `Secure`、`HttpOnly` 和 `SameSite` 属性。
- `deploy/docker.env` 含密码，只保存在目标电脑，不提交、不通过聊天发送。
- MySQL、Redis 和 RabbitMQ 业务端口未暴露到宿主机；后端和 RabbitMQ 管理端口只绑定 `127.0.0.1`。
- 默认启用 RabbitMQ 消息链路，vhost 与数据库同名；不要改成 `/`。
- 如需 Elasticsearch，应单独制作安装 IK 插件的固定版本镜像，验证中文分词及索引重建后再启用。
