# 免费技术内容社区 · 本地中间件

P0 阶段所需的中间件，全部通过 Docker Compose 管理。

本目录另有独立的 `docker-compose.web.yml` 与 `nginx/default.conf`，用于在 `127.0.0.1:8088` 提供前端静态文件并转发 Java API。推荐通过仓库根目录 `dev.ps1` 统一管理，操作见 [本地联调说明](../LOCAL-DEVELOPMENT.md)。原有 `docker-compose.yml` 的服务、端口和数据卷不变，普通中间件启动不会顺带启动 Nginx。

## 首次启动

```bash
# 在仓库根目录执行
cd infra
docker compose up -d
docker compose ps
```

或者双击 / 执行 `dev-up.ps1`（会先检查 Docker Desktop 是否运行）。

## 端口与访问地址

| 服务 | 宿主机地址 | 说明 |
|---|---|---|
| MySQL | `127.0.0.1:3307` | 原生 MySQL 在 3306，这里错开 |
| Redis | `127.0.0.1:6380` | 需密码，见 `.env` |
| RabbitMQ AMQP | `127.0.0.1:5672` | 应用连接 |
| RabbitMQ 管理台 | <http://localhost:15672> | 账号见 `.env` |
| MinIO S3 API | `127.0.0.1:9000` | 应用连接 |
| MinIO 控制台 | <http://localhost:9001> | 账号见 `.env` |
| Mailpit SMTP | `127.0.0.1:1025` | 应用发信 |
| Mailpit 收件箱 | <http://localhost:8025> | 查看验证码邮件 |
| Elasticsearch | `127.0.0.1:9200` | P1 搜索，默认不启动 |

## 账号密码

全部在 `.env` 中，该文件已被 `.gitignore` 排除。
`MYSQL_USER=app` 对 `free_content` 库有权限；MinIO 桶 `free-content` 已由 `minio-init` 自动创建，且为私有。

## 常用命令

```bash
# 查看状态
docker compose ps

# 查看某个服务日志
docker compose logs -f mysql
docker compose logs -f rabbitmq

# 停止（保留数据）
docker compose stop

# 停止并删除容器（保留数据卷）
docker compose down

# 停止并删除容器 + 数据卷（会清空数据库/Redis/MinIO 数据，慎用）
docker compose down -v

# 启动 Elasticsearch（P1）
docker compose --profile search up -d elasticsearch
```

## Spring Boot 连接参数

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3307/free_content?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC
    username: app
    password: ${MYSQL_PASSWORD}
  data:
    redis:
      host: 127.0.0.1
      port: 6380
      password: ${REDIS_PASSWORD}
  rabbitmq:
    host: 127.0.0.1
    port: 5672
    username: app
    password: ${RABBITMQ_PASSWORD}
  mail:
    host: 127.0.0.1
    port: 1025
    properties:
      mail.smtp.auth: false
      mail.smtp.starttls.enable: false

app:
  storage:
    endpoint: http://127.0.0.1:9000
    access-key: ${MINIO_ROOT_USER}
    secret-key: ${MINIO_ROOT_PASSWORD}
    bucket: free-content
```

## 内存限制

| 层级 | 配置位置 | 当前值 |
|---|---|---|
| WSL2 虚拟机总上限 | `%USERPROFILE%\.wslconfig` | `memory=8GB`、`processors=4`、`swap=2GB` |
| MySQL 容器 | compose `mem_limit` | 1 GiB |
| Redis 容器 | `mem_limit` + `maxmemory` | 256 MiB / 192 MiB，`volatile-lru` |
| RabbitMQ 容器 | compose `mem_limit` | 768 MiB |
| MinIO 容器 | compose `mem_limit` | 512 MiB |
| Mailpit 容器 | compose `mem_limit` | 128 MiB |
| Elasticsearch 容器 | `mem_limit` + JVM 堆 | 2 GiB / 1 GiB 堆 |

P0 全部启动时容器上限合计约 **2.75GiB**；P1 加上 Elasticsearch 后约 **4.75GiB**，都在 8GB 虚拟机上限内。

修改 `.wslconfig` 后需要执行 `wsl --shutdown` 再重启 Docker Desktop；修改 `mem_limit` 后执行 `docker compose up -d` 重建容器。
注意 `docker info` 显示的可用内存会略小于 8GB（约 7.76GiB），差额是 WSL 内核和 Docker 自身开销。

## 注意

- 所有端口只绑定 `127.0.0.1`，局域网其他机器访问不到。
- MySQL 容器时区为 UTC，与接口契约“输出统一 UTC”一致；日统计的 Asia/Shanghai 口径在应用层处理。
- `docker_data.vhdx` 已迁移到非系统盘的 `DockerData\docker-desktop` 目录。
- Elasticsearch 使用 1GB 堆，启动前确认 Docker Desktop 分配了足够内存。
