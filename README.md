# 星河印象城交易服务中心

基于 Spring Boot 和 Vue 的多店铺交易服务项目，包含商品、购物车、订单、支付流程和客服页面。推荐使用 Docker Compose 部署：它会启动前端、后端、MySQL、Redis 和 RabbitMQ，并自动执行数据库表结构迁移。

> 本仓库提供**源码和构建脚本**，不包含 Docker 镜像或 `images.tar`。从 GitHub 克隆后按下文“方式一”构建；完全离线的电脑请使用“方式二”制作并传输离线包。

## 运行前准备

- Windows：安装 Docker Desktop（切换到 **Linux containers**）和 PowerShell 7（`pwsh`）。确认 `docker compose version` 和 `pwsh --version` 能正常运行。
- 从源码构建时，构建电脑还需要能访问 Docker 镜像仓库以及 Maven、npm 依赖源；目标电脑使用离线包时不需要联网，也不需要单独安装 Java、Maven、Node.js 或 MySQL。
- 建议为 Docker Desktop 分配至少 4 核 CPU、8 GB 内存，并留出至少 10 GB 磁盘空间。离线包的构建电脑和目标电脑需要兼容的 CPU 架构。
- 以下命令均在 **PowerShell 7** 中执行。先进入仓库根目录；路径有空格时用引号包住。

## 方式一：从 GitHub 源码在线部署

```powershell
git clone https://github.com/Su-Shengzhe/Xinghe-Impression-City---Service-Center.git
Set-Location .\Xinghe-Impression-City---Service-Center
Copy-Item .\deploy\docker.env.example .\deploy\docker.env
notepad .\deploy\docker.env
```

在打开的 `deploy/docker.env` 中，**将所有 `CHANGE_ME` 替换成自己的强密码**，包括员工初始密码；即使暂未启用员工账号播种，也不能留下占位符。可按需要修改 `WEB_PORT`（默认 `8080`）。保存后启动 Docker Desktop，运行：

```powershell
docker compose --env-file .\deploy\docker.env -f .\compose.yaml -f .\compose.build.yaml up -d --build
docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps
```

第一次构建会下载镜像和依赖，可能需要几分钟。等 `backend`、`frontend` 等服务启动且后端健康后，在部署电脑打开 `http://localhost:8080`；其他电脑访问 `http://部署电脑IP:8080`。如果修改过 `WEB_PORT`，将地址中的 `8080` 换成设置的端口，并允许该端口通过宿主机防火墙。

后端健康检查只在部署电脑本机开放：`http://localhost:8081/actuator/health`（修改过 `BACKEND_PORT` 时使用新端口）。**首次使用新数据库，还须完成下文的店铺和员工初始化。**

## 方式二：制作离线包，发送到另一台电脑

在**能联网的构建电脑**上克隆源码、启动 Docker Desktop，然后于仓库根目录执行：

```powershell
pwsh -File .\deploy\build-offline-bundle.ps1
```

脚本会构建应用镜像、下载运行镜像，并在 `docker-release\xinghe-trade-center\` 生成 `images.tar`、Compose 文件、示例配置、部署脚本和校验文件。将**整个目录**复制到目标电脑；不要附带本机的 `.env` 或含真实密码的 `deploy/docker.env`。该脚本生成的是目录，不会自动制作 ZIP。

目标电脑启动 Docker Desktop（Linux containers），用 PowerShell 7 进入收到的 `xinghe-trade-center` 目录：

```powershell
Copy-Item .\deploy\docker.env.example .\deploy\docker.env
notepad .\deploy\docker.env
# 保存文件，并将所有 CHANGE_ME 替换为自己的密码后再继续
pwsh -File .\deploy\import-and-start.ps1
```

此脚本会导入 `images.tar` 并以 `--no-build --pull never` 启动，无须目标电脑连接镜像仓库。随后同样完成下文的首次初始化。详细说明见 [Docker 部署文档](deploy/DEPLOYMENT.md)。

## 首次初始化：店铺与员工账号

这一步只在**新数据库第一次部署**时做。Flyway 会在后端启动时自动建表，但不会自动创建正式店铺和员工账号。下列命令在含有 `compose.yaml` 的目录执行，在线源码部署和离线包部署均适用。

1. 确认 `docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps` 显示后端已健康，然后创建店铺（脚本会先检查是否已有同 ID 店铺，不会覆盖）：

   ```powershell
   pwsh -File .\deploy\initialize-store.ps1 -StoreId 1 -StoreName '星河印象城店铺'
   ```

2. 编辑 `deploy/docker.env`：确认 `XINGHE_AUTH_EMPLOYEE_SEED_USERNAME` 是你要使用的员工登录名、`XINGHE_AUTH_EMPLOYEE_SEED_PASSWORD` 是至少 8 位的强密码、`XINGHE_AUTH_EMPLOYEE_SEED_STORE_ID=1`，再将 `XINGHE_AUTH_EMPLOYEE_SEED_ENABLED` 改为 `true`。仅重建后端容器，让一次性播种器执行：

   ```powershell
   docker compose --env-file .\deploy\docker.env -f .\compose.yaml up -d --force-recreate backend
   docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps
   ```

3. 用该员工账号登录验证。**随即把 `XINGHE_AUTH_EMPLOYEE_SEED_ENABLED` 改回 `false`**，再执行一次上面的 `up -d --force-recreate backend` 命令。播种器不会覆盖已有账号；一直保持开启会让后续重启失败。顾客账号可在页面注册。

`XINGHE_SEED_ENABLED` 在 Docker 部署中固定为 `false`，不会自动生成演示商品。初始化店铺后，可由员工在页面创建商品。

## 日常操作与故障排查

```powershell
# 查看容器状态与后端日志
docker compose --env-file .\deploy\docker.env -f .\compose.yaml ps
docker compose --env-file .\deploy\docker.env -f .\compose.yaml logs --tail 200 backend

# 停止或重新启动；保留数据库等持久化卷
docker compose --env-file .\deploy\docker.env -f .\compose.yaml down
docker compose --env-file .\deploy\docker.env -f .\compose.yaml up -d --no-build
```

- **镜像下载出现 EOF、403 或连接超时**：先确认 Docker Desktop 的网络或 HTTPS 代理能够访问 Docker Hub；失效的镜像加速地址也会导致 403。源码构建还需能下载 Maven、npm 依赖。代理地址必须是你自己正在使用且可连接的代理服务地址，不能随意填写示例端口。
- **`backend` 不健康**：先看上面的后端日志，检查 `deploy/docker.env` 是否仍有占位符、MySQL/RabbitMQ 是否健康。MySQL、Redis、RabbitMQ 的数据保存在 Docker 卷里；不要为了重试而执行 `docker compose down -v`，它会删除持久化数据。
- **页面无法从其他电脑访问**：检查 `WEB_PORT`、部署电脑的局域网 IP 与防火墙。后端端口和 RabbitMQ 管理端口默认只绑定本机 `127.0.0.1`；前端端口对网络开放。
- **重新运行员工播种后启动失败**：确认账号已创建，然后把 `XINGHE_AUTH_EMPLOYEE_SEED_ENABLED` 恢复为 `false`，重建后端容器。

准备公开部署时，应在前端增加 HTTPS 反向代理，并备份 MySQL。不要把 `deploy/docker.env`、根目录 `.env`、数据库密码或离线镜像包提交到 GitHub。更多部署与升级说明见 [deploy/DEPLOYMENT.md](deploy/DEPLOYMENT.md)；运行与构建依赖见 [requirements.txt](requirements.txt)（它是依赖清单，**不是** Python `pip install` 文件）。

## 源码结构

| 路径 | 内容 |
| --- | --- |
| `hd/` | Java 17、Spring Boot 后端；Maven 依赖见 `hd/pom.xml` |
| `qd/` | Vue 3、Vite 前端；npm 依赖见 `qd/package.json` 和 `qd/package-lock.json` |
| `compose.yaml` | MySQL、Redis、RabbitMQ、前后端运行配置 |
| `compose.build.yaml` | 从源码构建前后端镜像 |
| `deploy/` | 配置模板、离线打包、导入启动和店铺初始化脚本 |

如果要在 Docker 之外直接运行源码，需要自行准备 MySQL、Redis，以及 Java 17、Maven、Node.js。可从 `.env.example` 复制根目录 `.env`，指向**新建的独立数据库**；默认关闭的 RabbitMQ、Elasticsearch 和 AI 客服不必为本地基础体验启用。后端在 `hd/` 中执行 `mvn spring-boot:run`（默认端口 `8081`），前端在 `qd/` 中执行 `npm ci`、`npm run dev`（默认地址 `http://localhost:5173`，`/api` 会代理到 `localhost:8081`）。根目录 `.env` 含密码，不要提交。
