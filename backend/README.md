# 免费技术内容社区 · 后端

Spring Boot 4.1.2 + JDK 17 后端，已实现验证码申请及邮箱注册，对应本地 API 契约（`project-docs` 目录，不随仓库分发）。

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

后端按业务模块分包，模块内部按职责分层。完整目录、类职责和调用顺序见
[后端结构与阅读指南](STRUCTURE.md)。

```text
backend/
├── pom.xml
├── run-local.ps1
└── src/
    ├── main/
    │   ├── java/com/freecontent/community/
    │   │   ├── FreeContentApplication.java
    │   │   ├── auth/
    │   │   │   ├── controller/          # HTTP入口
    │   │   │   ├── service/             # 验证码申请、注册两个主业务入口
    │   │   │   │   └── transaction/     # 写事务、验证码校验及错码状态管理
    │   │   │   ├── ratelimit/           # Redis限流
    │   │   │   ├── job/                 # 后台邮件投递
    │   │   │   ├── security/            # 密码哈希、验证码摘要及加密
    │   │   │   ├── config/              # 认证配置及参数
    │   │   │   ├── dto/                 # 接口请求、回执
    │   │   │   ├── entity/              # 验证码及邮件任务实体
    │   │   │   ├── mapper/              # 数据库访问
    │   │   │   └── model/               # 验证码用途枚举
    │   │   ├── user/                    # 用户资料、角色及其数据库访问
    │   │   ├── common/
    │   │   │   ├── api/        # ApiResponse、RequestIdFilter
    │   │   │   ├── error/      # RFC 9457 Problem Details
    │   │   │   └── validation/ # 通用字段校验
    │   │   └── config/         # MyBatis-Plus、OpenAPI
    │   └── resources/
    │       ├── application.yml
    │       ├── db/migration/   # Flyway 脚本
    │       └── redis/          # Redis Lua脚本
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

1. 接入邮箱密码登录、退出与Redis不透明Token会话；
2. 按P0契约继续实现内容、修订、审核和媒体业务；
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
mvn "-Dtest=*Test" test

# 先加载本地环境变量，再验证真实MySQL事务回滚
mvn "-Dtest=EmailChallengeTransactionIT" test
```

当前已完成验证码申请、邮件投递和邮箱验证码注册；登录和密码找回继续分步实现。

## 邮箱验证码注册

接口为 `POST /api/v1/auth/registrations`，无需登录，使用 `REGISTER` 用途的申请：

```json
{
  "email": "creator@example.com",
  "password": "ExampleRegistrationPassword!",
  "challengeId": "申请验证码接口返回的challengeId",
  "code": "用户从邮件中读取的六位验证码",
  "displayName": "创作者"
}
```

成功返回 HTTP 201 和 `CurrentUser`：邮箱已验证，角色为 `USER`、
账号状态为 `ACTIVE`、资料版本为 1，满足创作资格。注册不创建登录会话，
响应不包含密码、验证码、密码哈希或 Token，也不设置登录 Cookie。

服务先按来源和规范化邮箱限流，再验证挑战；只有正确验证码才进入密码哈希。
密码计算使用 Spring Security Crypto 的 Argon2id 编码器和 Bouncy Castle 1.86，
初始参数为 19 MiB 内存、2 次迭代、并行度 1、16 字节盐和32字节哈希。
BC版本与当前MinIO间接依赖保持一致，并已显式声明。哈希带 `{argon2id}` 算法标识，
默认最多同时执行两个密码编码任务，繁忙时返回503，避免密码计算耗尽内存。

密码支持完整的 12～128 个Unicode字符，保留首尾空格，不做静默截断或Unicode规范化。
昵称支持1～40个Unicode字符。长度校验按码点计算，不把一个补充平面字符误算为两个。
请求对象的 `toString()` 隐去凭证，接口使用独立DTO返回账号资料。

密码哈希在数据库事务之外计算；写事务重新锁定并校验挑战，创建账号和基础角色、
消费验证码、取消未完成投递任务。任何中途数据库异常都会回滚整组变更。
错码返回false先提交失败计数，再在事务外返回400 `CHALLENGE_INVALID`，
默认第5次错误将该挑战锁定。不同邮箱、不同用途、过期或已消费的申请统一拒绝。
规范化邮箱唯一索引保证不能重复创建账号；有效新验证码用于已有邮箱时返回409
`REGISTRATION_NOT_ALLOWED`，不消费新验证码。并发使用同一申请最多一次成功。

注册进入业务后的初始限额为来源每分钟30次、邮箱每小时20次，均可配置。
来源使用TCP连接地址，与验证码申请接口使用相同的可信代理边界。
配置在 `app.auth.registration`；新增表由 `V3__user_account_and_roles.sql` 管理。

```powershell
# 不连接中间件的单元/Web测试
mvn "-Dtest=*Test" test

# 加载infra/.env后运行全部测试（包括真实MySQL的事务、并发与错码锁定测试）
# 验证时关闭后台投递，避免测试上下文处理其他待发送邮件
$env:EMAIL_CHALLENGE_DELIVERY_ENABLED = "false"
mvn "-Dtest=*Test,*Tests,*IT" test
```
