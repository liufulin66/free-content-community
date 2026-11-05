# 免费技术内容社区 · 后端

Spring Boot 4.1.2 + JDK 17 的后端骨架，对应本地 API 契约（`project-docs` 目录，不随仓库分发）。

## 技术栈

| 组件 | 版本/说明 |
|---|---|
| Spring Boot | 4.1.2 |
| JDK | 17（Spring Boot 4 基线也是 17） |
| Web | `spring-boot-starter-webmvc`（Boot 4 已弃用 `spring-boot-starter-web`） |
| ORM | MyBatis-Plus `mybatis-plus-spring-boot4-starter` 3.5.18 |
| 数据库 | MySQL 8.0（Docker，宿主机端口 3307） |
| 迁移 | Flyway（`src/main/resources/db/migration`） |
| 缓存/会话 | Redis 7（Docker，宿主机端口 6380） |
| 消息 | RabbitMQ 4（Docker，5672 / 15672） |
| 对象存储 | MinIO（Docker，9000 / 9001） |
| 接口文档 | springdoc-openapi 3.1.1，OpenAPI 3.1 |

## 启动前

先启动中间件（在仓库根目录执行）：

```powershell
cd infra
.\dev-up.ps1
```

确认 `docker compose ps` 中 MySQL、Redis、RabbitMQ、MinIO、Mailpit 均为 healthy。

## 启动后端

方式一（推荐，自动读取 `../infra/.env`，在仓库根目录执行）：

```powershell
cd backend
.\run-local.ps1
```

方式二（手动设置环境变量后）：

```bash
mvn spring-boot:run
```

需要设置的环境变量：`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RABBITMQ_PASSWORD`，其余有默认值（3307 / 6380 / 5672 / app）。

## 验证

| 地址 | 说明 |
|---|---|
| <http://localhost:8080/actuator/health> | 数据库、Redis、RabbitMQ 健康状态 |
| <http://localhost:8080/v3/api-docs> | OpenAPI JSON |
| <http://localhost:8080/swagger-ui.html> | Swagger UI |

## 目录结构

```text
backend/
├── pom.xml
├── run-local.ps1
└── src/
    ├── main/
    │   ├── java/com/freecontent/community/
    │   │   ├── FreeContentApplication.java
    │   │   ├── common/
    │   │   │   ├── api/        # ApiResponse、RequestIdFilter
    │   │   │   └── error/      # RFC 9457 Problem Details
    │   │   └── config/         # MyBatis-Plus、OpenAPI
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/   # Flyway 脚本
    └── test/java/com/freecontent/community/
```

## 约定

- 成功响应统一 `{ "data": ..., "requestId": "...", "serverTime": "..." }`；204 不包装。
- 失败响应使用 `application/problem+json`，字段含 `type/title/status/detail/instance/code/requestId/violations`。
- 所有 ID 使用不透明字符串；`mybatis-plus.global-config.db-config.id-type=assign_uuid` 生成 32 位 UUID 字符串。
- 时间统一 UTC；MySQL 容器时区和 JDBC `connectionTimeZone` 均为 UTC。
- 业务表按契约分版本新增 Flyway 脚本，已执行的脚本不要修改。

## Spring Boot 4 注意事项（本次踩过的坑）

1. **Web starter**：`spring-boot-starter-web` 已标记 deprecated，使用 `spring-boot-starter-webmvc`。
2. **Flyway**：Boot 4 把自动配置拆到独立模块，必须用 `spring-boot-starter-flyway`；只加 `flyway-core` 不会执行迁移。
3. **Jackson 3**：`spring.jackson.serialization.write-dates-as-timestamps` 在 Jackson 3 已不存在，配置它会导致启动失败；Jackson 3 默认输出 ISO-8601 字符串。
4. **HTTP 422**：使用 `HttpStatus.UNPROCESSABLE_CONTENT`；`UNPROCESSABLE_ENTITY` 在 Spring 7 已过时。
5. **Git Bash 环境变量**：不要把 `/` 作为环境变量值，MSYS 会把它转成 Git 安装目录下的路径。RabbitMQ vhost 使用 Spring 默认的 `/`，不通过 `.env` 传入。

## 下一步

1. 按 P0 契约建账号、内容、修订、审核、媒体等表和 Mapper；
2. 接入 Redis 不透明 Token 会话、限流、幂等表；
3. 接入 RabbitMQ + Outbox 事件投递；
4. 按 operationId 实现 50 个 P0 接口。

## 邮箱验证码接口

已实现 `POST /api/v1/auth/email-challenges`，无需登录：

```json
{
  "purpose": "REGISTER",
  "email": "creator@example.com"
}
```

`purpose` 支持 `REGISTER` 和 `PASSWORD_RESET`。成功返回 HTTP 202，
`data` 包含 `challengeId`、`expiresAt`、`resendAfter`，不返回验证码。
202 表示挑战及邮件任务已经在同一个 MySQL 事务中保存，邮件由后台投递。
本地邮件在 Mailpit 网页查看，默认地址为 <http://localhost:8025>。

初始配置：有效期 600 秒、同邮箱同用途重发间隔 60 秒、邮箱每小时 5 次、
连接来源每小时 100 次。邮箱次数跨用途共享；小时窗口从该计数首次申请开始。
Redis Lua 原子检查配额，计数表示申请次数，数据库失败不自动退还配额。
来源默认取 TCP 连接地址，不直接信任客户端传入的 `X-Forwarded-For`。
经过反向代理时，需要按实际可信代理边界配置来源解析，避免所有访问共用代理IP配额。

同邮箱同用途重发会在事务内让旧申请失效、取消旧待发送任务并建立新申请。
已经发出的旧邮件无法撤回，但旧申请不能再完成验证。
后台采用 MySQL 条件认领、60 秒租约和认领令牌，扫描间隔默认 1 秒。
SMTP 默认最多尝试 3 次，失败按 5 秒、10 秒等间隔退避；任务过期或失效后停止投递。
进程在 SMTP 接收成功但数据库回写前退出，可能导致同一验证码重复投递。
所有终结任务会清理验证码密文；数据库租约恢复不依赖进程内队列。

本地运行需要在已忽略的 `infra/.env` 中配置以下两个独立随机密钥：

- `EMAIL_CHALLENGE_MAC_KEY_BASE64`：32 字节随机值的 Base64，用于 HMAC-SHA256。
- `EMAIL_CHALLENGE_ENCRYPTION_KEY_BASE64`：另一个 32 字节随机值的 Base64，用于 AES-GCM。

本次开发已为现有本地环境生成并保存密钥。通过 `run-local.ps1` 运行会读取该文件；
通过 IDE 直接运行时，需要配置相同环境变量。密钥须在重启间保持稳定。
当前实现使用单组带标识的密钥；更换前应处理完旧申请和任务，不能直接丢弃旧密钥。
其他可调参数见 `application.yml` 的 `app.auth.email-challenge`。
验证码申请表仅保存 HMAC 摘要，任务暂存绑定任务ID和申请ID的 AES-GCM 密文，
原始验证码和密钥不进入接口响应或普通日志。

普通测试与显式集成测试：

```powershell
# 不连接中间件的单元/Web测试
mvn "-Dtest=GlobalExceptionHandlerTest,EmailChallengeControllerTest,EmailChallengeCryptoTest,EmailChallengeServiceTest,EmailChallengeWriterTest,EmailDeliveryWorkerTest" test

# 先加载本地环境变量，再验证真实MySQL事务回滚
mvn "-Dtest=EmailChallengeTransactionIT" test
```

该步骤只完成验证码申请及邮件投递；注册、登录和消费验证码接口在后续实现。
