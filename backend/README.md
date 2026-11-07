# 免费技术内容社区 · 后端

Spring Boot 4.1.2 + JDK 17 后端，已实现验证码申请、邮箱注册、密码登录、退出、验证码重置、当前账号改密、资料查询与编辑、Redis会话认证、上传策略查询、私有上传会话创建、查询续签、取消与后台文件回收、完成确认、媒体处理任务及本人素材校验结果查询，对应本地 API 契约（`project-docs` 目录，不随仓库分发）。

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

需要设置：`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RABBITMQ_PASSWORD`、`EMAIL_CHALLENGE_MAC_KEY_BASE64`、`EMAIL_CHALLENGE_ENCRYPTION_KEY_BASE64`；媒体上传还需要 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`。`run-local.ps1` 会加载 `infra/.env`；复制的 `.env.example` 已包含本地示例密钥，生产必须重新生成。
前端入口 `http://localhost:8088`（nginx）和 `http://localhost:5173`（Vite）已加入默认 CORS 白名单；换端口时通过 `CORS_ALLOWED_ORIGINS` 覆盖并重启后端。

## 验证

| 地址 | 说明 |
|---|---|
| <http://localhost:8080/actuator/health> | 数据库、Redis、RabbitMQ 健康状态 |
| <http://localhost:8080/v3/api-docs> | OpenAPI JSON |
| <http://localhost:8080/swagger-ui.html> | Swagger UI |

## 目录结构

后端按业务模块分包，模块内部按职责分层。完整目录、类职责和调用顺序见
[后端结构与阅读指南](STRUCTURE.md)。

验证码、注册、密码登录、退出和验证码重置密码统一由`auth/controller/AuthController.java`提供HTTP入口，
分别依赖EmailChallengeService、RegistrationService、LoginService、LogoutService和PasswordResetService接口，
当前账号资料查询、编辑和改密由`user/controller/MeController.java`提供入口，依赖CurrentUserService、UserProfileService和PasswordChangeService接口。
认证相关的五个接口及实现归`auth/service`；当前账号改密的DTO、Service、事务、限流和配置统一归`user`。
后续业务代码遵循项目根目录[AGENTS.md](../AGENTS.md)中的业务结构约定。

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
    │   │   │   ├── service/             # 验证码、注册、登录接口及会话认证组件
    │   │   │   │   ├── impl/            # 五个认证业务接口的实现
    │   │   │   │   └── transaction/     # 写事务、验证码校验及错码状态管理
    │   │   │   ├── ratelimit/           # Redis限流
    │   │   │   ├── job/                 # 后台邮件投递
    │   │   │   ├── security/            # 密码哈希、验证码摘要及加密
    │   │   │   ├── session/             # Redis会话签发、查询及数量限制
    │   │   │   ├── config/              # 认证配置及参数
    │   │   │   ├── dto/                 # 接口请求、回执
    │   │   │   ├── entity/              # 验证码及邮件任务实体
    │   │   │   ├── mapper/              # 数据库访问
    │   │   │   └── model/               # 验证码用途枚举
    │   │   ├── user/                    # 当前账号业务、用户资料、角色及数据访问
    │   │   │   ├── controller/          # MeController
    │   │   │   ├── dto/                 # 改密请求及用户资料响应
    │   │   │   ├── service/
    │   │   │   │   ├── impl/            # 当前账号业务实现
    │   │   │   │   └── transaction/     # 用户业务事务
    │   │   │   ├── ratelimit/           # 用户业务限流
    │   │   │   └── config/              # 用户业务配置
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

JSON请求严格匹配契约类型：数字和布尔值不会自动转换成字符串，枚举只接受名称，不接受数字序号或序号字符串。
类型不匹配返回400 `MALFORMED_REQUEST`；缺失、空值和字符串内容继续按各接口的字段规则校验。
Swagger显式声明验证码申请202、注册201和登录201，与实际成功响应一致。

## 下一步

1. 实现媒体处理worker、真实文件校验及素材READY流程，随后开放头像素材绑定；
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
来源默认取 TCP 对端地址；仅当对端属于可信代理网段（`TRUSTED_PROXY_PATTERN`）时才采用 `X-Forwarded-For`，直接暴露时忽略客户端伪造头。
经过反向代理时，需要按实际可信代理边界配置来源解析，避免所有访问共用代理IP配额。

同邮箱同用途重发会在事务内让旧申请失效、取消旧待发送任务并建立新申请。
已经发出的旧邮件无法撤回，但旧申请不能再完成验证。
后台采用 MySQL 条件认领、60 秒租约和认领令牌，扫描间隔默认 1 秒。
SMTP 默认最多尝试 3 次，失败按 5 秒、10 秒等间隔退避；任务过期或失效后停止投递。
SMTP连接、读取和写入等待默认分别为3000、5000、5000毫秒，避免服务无响应时无限占用调度线程。
可通过`MAIL_SMTP_CONNECTION_TIMEOUT_MILLIS`、`MAIL_SMTP_READ_TIMEOUT_MILLIS`和`MAIL_SMTP_WRITE_TIMEOUT_MILLIS`覆盖，值应为正整数毫秒。
这些是各阶段的Socket等待时限，不是整次投递的总时限；任务租约不会中断网络调用。
进程在 SMTP 接收成功但数据库回写前退出，可能导致同一验证码重复投递。
所有终结任务会清理验证码密文；数据库租约恢复不依赖进程内队列。

本地运行需要在已忽略的 `infra/.env` 中配置以下两个独立随机密钥：

- `EMAIL_CHALLENGE_MAC_KEY_BASE64`：32 字节随机值的 Base64，用于 HMAC-SHA256。
- `EMAIL_CHALLENGE_ENCRYPTION_KEY_BASE64`：另一个 32 字节随机值的 Base64，用于 AES-GCM。

`.env.example` 已包含仅用于本地开发的示例值；复制成 `.env` 即可启动。生产环境必须用
`openssl rand -base64 32` 重新生成两个不同值。`run-local.ps1` 会读取该文件；
通过 IDE 直接运行时，需要配置相同环境变量。密钥须在重启间保持稳定。
当前实现使用单组带标识的密钥；更换前应处理完旧申请和任务，不能直接丢弃旧密钥。
其他可调参数见 `application.yml` 的 `app.auth.email-challenge`。
验证码申请表仅保存 HMAC 摘要，任务暂存绑定任务ID和申请ID的 AES-GCM 密文，
原始验证码和密钥不进入接口响应或普通日志。

普通测试与集成测试：

```powershell
# 不连接中间件的单元/Web测试（Surefire）
mvn test

# 加载 infra/.env 并启动中间件后，运行单元测试和全部 *IT（Surefire + Failsafe）
$env:EMAIL_CHALLENGE_DELIVERY_ENABLED = "false"
mvn verify

# 没有中间件时只验证单元/Web测试和打包
mvn -DskipITs verify
```

当前已完成验证码申请、邮件投递、邮箱验证码注册、密码登录、退出及验证码重置密码。

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
来源使用TCP对端地址，与验证码申请接口共用同一套可信代理边界。
配置在 `app.auth.registration`；新增表由 `V3__user_account_and_roles.sql` 管理。

```powershell
# 不连接中间件的单元/Web测试
mvn test

# 加载infra/.env并启动中间件后运行全部测试（含真实MySQL/Redis/MinIO）
# 验证时关闭后台投递，避免测试上下文处理其他待发送邮件
$env:EMAIL_CHALLENGE_DELIVERY_ENABLED = "false"
mvn verify
```

## 密码登录与Redis会话

`POST /api/v1/auth/sessions`为公开接口，输入邮箱、密码及可选的设备名称：

```json
{
  "email": "creator@example.com",
  "password": "ExampleRegistrationPassword!",
  "deviceLabel": "开发浏览器"
}
```

成功返回201，`data`为`accessToken、tokenType=Bearer、expiresAt、sessionId、user`。
注册后仍须单独登录。登录密码长度为1～128个Unicode字符；邮箱沿用注册的trim和
大小写不敏感匹配，密码原样参与Argon2id校验，不trim、不截断。deviceLabel最多80个字符，
仅用于显示，不作为设备身份或权限依据。请求和签发结果的toString不输出凭证。

邮箱不存在和密码错误统一401 `AUTHENTICATION_FAILED`；未知邮箱执行虚拟哈希校验。
密码校验与注册编码共用最多2个计算名额。密码正确后再判断邮箱验证和账号状态，
未验证邮箱返回403 `EMAIL_VERIFICATION_REQUIRED`，禁用账号返回403 `ACCOUNT_RESTRICTED`。
WRITE_BLOCKED允许登录、阅读及账号安全操作，canCreate=false，业务写入返回403。

默认策略均在`app.auth.login`配置：

| 配置 | 默认值 | 语义 |
| --- | --- | --- |
| ttl-seconds / LOGIN_TTL_SECONDS | 86400 | 每次密码登录成功签发独立的24小时会话，读取不续期 |
| max-sessions / LOGIN_MAX_SESSIONS | 5 | 每账号最多5个有效会话，超限撤销最早创建的会话 |
| ip-per-minute-limit / LOGIN_IP_PER_MINUTE_LIMIT | 30 | 同TCP连接来源每分钟进入登录业务的次数 |
| email-window-limit / LOGIN_EMAIL_WINDOW_LIMIT | 10 | 同规范化邮箱在窗口内的登录尝试次数 |
| email-window-seconds / LOGIN_EMAIL_WINDOW_SECONDS | 900 | 邮箱计数窗口15分钟，从首次获准尝试开始 |
| redis-key-prefix / LOGIN_REDIS_KEY_PREFIX | fc:auth: | Redis登录配额和会话键的前缀 |

限流在数据库查询和密码计算之前执行，所有获准尝试均计数，包括错误密码与未知邮箱。
超限429附Retry-After；Redis/MySQL故障或无法确认写入结果返回503，不签发无法确认的凭证。
来源仅在TCP对端属于可信代理网段时采用X-Forwarded-For；生产部署通过TRUSTED_PROXY_PATTERN配置真实代理网段。

Token由32字节安全随机数生成，原文只在登录响应中返回；Redis键使用SHA-256摘要，
会话保存sessionId、userId、authVersion、创建/过期/活动时间和设备说明。
Lua原子维护会话及用户索引、清理失效项和淘汰最早会话。索引丢失时孤立会话不能继续认证。
所有会话相关键使用同一个`{sessions}`槽标记，适配当前单Redis部署；不跨MySQL和Redis承诺事务回滚。

受保护请求携带`Authorization: Bearer <accessToken>`。Spring Security过滤器链不启用
HttpSession、表单登录、HTTP Basic或Cookie认证；公开认证入口不校验客户端残留的Bearer。
认证会核对Redis会话及MySQL当前账号、角色、authVersion，失效会话返回401
`AUTHENTICATION_REQUIRED`。错误沿用Problem Details、requestId和no-store规范。
Bearer由客户端显式发送，因此本方案不启用Cookie认证所需的CSRF机制；CORS继续使用来源白名单。

V4迁移只给既有app_user增加auth_version，默认1，保留原有账号及数据，独立于资料version。
验证码重置和已登录改密均已在数据库事务中递增auth_version；后续禁用账号也必须递增，并执行相应会话清理；
认证版本核对保证旧凭证不会因清理与登录并发而继续有效，但不会撤回已经通过认证的在途请求。

当前已实现GET /api/v1/me，P1会话管理接口仍待实现；浏览器Token保存方式留到前端对接时确认。
后续新增公开/可选认证和后台接口时，须按契约补充Security规则及资源归属检查。

2026-11-05验证：全部81项测试通过，包括真实HTTP登录、MySQL V4迁移、Redis并发创建上限、
最早会话淘汰、不续期、认证版本失效、角色即时复核及登录限流。
集成测试关闭邮件后台投递，只清理自身唯一前缀与账号，不停止中间件、不清空数据库。

## 退出当前会话

`DELETE /api/v1/auth/sessions/current`需要有效的`Authorization: Bearer <accessToken>`，无请求体。
成功返回204且无响应体，不使用data包装；前端清理本地Token与用户状态。
已失效Token返回401 `AUTHENTICATION_REQUIRED`时，前端同样完成本地退出。

认证过滤器先确认会话身份，内部SessionPrincipal保存Token摘要、会话ID及当前用户。
LogoutServiceImpl调用RedisSessionStore.revoke，以Lua复核会话归属，原子删除当前会话Hash
和用户会话ZSet中的对应摘要。最后一个成员移除后，Redis自动删除空索引。
退出只撤销当前会话，不修改auth_version或资料version，其他设备继续登录；WRITE_BLOCKED允许退出。

重复HTTP退出通常在认证阶段返回401。两个请求都已认证后并发退出时，后到的请求确认记录已不存在，
清理残留索引后可返回204。Redis操作失败或结果无法确认返回503 `DEPENDENCY_UNAVAILABLE`，
不能作为服务器退出成功处理。Lua在写入前检查索引类型，避免错误类型造成部分清理。
退出不会撤回已通过认证的在途请求；后续旧Token请求被拒绝，活动时间更新也不会重新创建会话。

2026-11-06验证：全部96项测试通过，新增真实HTTP退出、旧Token拒绝、保留其他会话、
WRITE_BLOCKED退出、归属检查、索引清理及并发读取不恢复会话的验证；API文档声明Bearer和204空响应。

## 验证码重置密码

先调用`POST /api/v1/auth/email-challenges`，指定`purpose=PASSWORD_RESET`及账号邮箱。
重置时调用公开接口`POST /api/v1/auth/password-resets`，提交email、challengeId、code、newPassword。
challengeId使用发送接口返回的data.challengeId，code使用邮件中的真实六位验证码，不提交说明性占位文字。
成功204且无响应体，不签发新Token；用户必须用新密码重新登录，之前的全部会话都已失效。

邮箱使用与注册、登录相同的规范化规则。新密码12～128个Unicode字符，保留首尾空格，
不截断、不规范化、不记录日志。验证码必须与邮箱及PASSWORD_RESET用途匹配，并处于有效、未消费且未锁定状态。
错误、过期、重放、错邮箱或错用途统一400 CHALLENGE_INVALID。
正确验证码对应的邮箱没有账号时，也消费挑战并返回204，不创建账号，避免通过响应探测账号是否存在。

验证码预检查和最终写入之间不持有数据库事务，只有有效验证码才计算Argon2id哈希。
最终事务再次锁定挑战和账号，在同一MySQL事务更新密码及auth_version、消费挑战、取消未完成邮件任务。
发生异常时整组回滚；错码以false返回，让次数提交后再在事务外返回400。
注册与重置共用EmailChallengeVerificationService，但用途由各自后端入口固定，不接受客户端覆盖。
账号状态、角色、邮箱验证状态和资料version保持不变，重置密码不能解除DISABLED或WRITE_BLOCKED。

重置事务提交后，认证链依据MySQL中的新auth_version拒绝全部旧Token。
RedisSessionStore.revokeBeforeVersion只清理旧认证版本的会话，保留提交后新登录的会话。
Redis物理清理失败时记录日志，仍返回204；旧会话已逻辑失效，残留由之后登录及TTL清理。
MySQL写入提交前的依赖故障返回503，不能将MySQL和Redis当成同一个可回滚事务。

app.auth.password-reset初始配置：

| 配置 / 环境变量 | 默认值 |
| --- | --- |
| max-code-failures / PASSWORD_RESET_MAX_CODE_FAILURES | 每个挑战输错5次后锁定 |
| ip-per-minute-limit / PASSWORD_RESET_IP_PER_MINUTE_LIMIT | 同来源每分钟30次 |
| email-hourly-limit / PASSWORD_RESET_EMAIL_HOURLY_LIMIT | 同邮箱每小时20次 |
| redis-key-prefix / PASSWORD_RESET_REDIS_KEY_PREFIX | fc:auth: |

提交配额以独立的{password-reset}键空间计数，计数窗口从首次获准请求开始，超限429附Retry-After。
来源取TCP对端地址，仅当对端属于可信代理网段时采用X-Forwarded-For。验证码发送仍使用原有发送配额；会话清理沿用登录会话键空间。
不新增数据表或迁移，复用现有挑战、邮件任务、账号和auth_version字段。

2026-11-06验证：全部129项测试通过，覆盖真实HTTP申请挑战、重置、旧密码与旧Token拒绝、
新密码登录、未知账号统一响应、错码锁定、状态保留、同挑战并发、事务回滚、
Redis清理失败后的逻辑撤销、清理期间新登录保留，以及注册和退出流程回归。

## 修改当前账号密码

`PUT /api/v1/me/password`需要有效Bearer身份，JSON请求仅包含currentPassword和newPassword。
目标用户由认证上下文确定，不接收客户端传入的userId或邮箱。两次新密码确认由前端比较，
不增加confirmPassword字段；未知字段按现有规范拒绝。成功204且无响应体，不签发新Token，前端清理身份并引导重新登录。

当前密码支持1～128个Unicode字符，新密码支持12～128个字符，原样参与Argon2id计算。
旧密码使用PasswordHasher.matches校验，新密码使用encode生成带盐哈希，两者共享既有计算并发限制。
旧密码错误返回401 AUTHENTICATION_FAILED，保留原密码与会话；前端应在表单提示，不作为会话失效处理。
无效或过时身份返回401 AUTHENTICATION_REQUIRED；WRITE_BLOCKED允许账号安全操作，DISABLED拒绝访问。

认证上下文携带认证时的authVersion。写事务锁定账号，再核对该版本、已验证的旧密码哈希和账号状态，
随后原子更新password_hash与auth_version。若校验与写入之间发生了其他改密或找回，请求被拒绝，不覆盖新凭证。
账号资料version、角色、邮箱验证状态与账号限制保持不变。成功后包括当前会话在内的全部旧Token失效。
提交后复用旧版本会话清理；新登录会话保留，Redis清理失败记录日志仍返回204，旧Token仍因版本不匹配被拒绝。

app.user.password-change配置为同来源每分钟30次、同用户每小时20次，采用独立{password-change}配额键。
环境变量为PASSWORD_CHANGE_IP_PER_MINUTE_LIMIT、PASSWORD_CHANGE_USER_HOURLY_LIMIT和PASSWORD_CHANGE_REDIS_KEY_PREFIX。
所有获准尝试均计数，包括错误旧密码，超限429附Retry-After；不会因此锁定账号。
来源取TCP对端地址，仅当对端属于可信代理网段时采用X-Forwarded-For。不新增表或迁移，复用现有账号与认证版本字段。

当前账号改密的DTO、Service接口及实现、事务、限流、配置均在user模块，测试同样归user。
密码哈希、认证上下文及会话存储继续复用auth的既有安全能力；不因技术复用改变用户业务归属。
本次结构迁移保留接口及PASSWORD_CHANGE_*环境变量，Redis键名和计数语义保持不变。

2026-11-06验证：全部153项测试通过，覆盖真实HTTP改密、旧密码错误时保留会话、全会话失效、
新密码登录、状态限制、并发凭证变更保护、数据库写入失败、清理失败、新登录保留与独立限流；
注册、登录、退出和验证码重置流程回归通过。

## 获取当前用户资料

`GET /api/v1/me`需要有效的`Authorization: Bearer <accessToken>`，无请求体，不使用客户端用户ID作为查询目标。
成功200，统一包装为data、requestId、serverTime；data为已有CurrentUser契约，包含账号资料、当前角色、账号状态及canCreate。
avatarAssetId为空时仍返回null。密码哈希、Token、Token摘要、sessionId和authVersion不进入响应。

认证链先校验Redis会话，再读取MySQL当前账号和角色并核对认证版本与状态，构建本次请求的SessionPrincipal。
MeController调用user模块的CurrentUserService，直接复用其中的CurrentUser，避免在同一次请求中再次查询数据库。
后续请求会重新认证，资料和角色变动不会长期停留在登录时的快照。

WRITE_BLOCKED允许查询，返回canCreate=false；DISABLED返回403 ACCOUNT_RESTRICTED。
缺少、无效、过期、已撤销Token或认证版本过时返回401 AUTHENTICATION_REQUIRED；Redis/MySQL不可用返回503。
响应使用Cache-Control: no-store，读取不修改资料version、auth_version，也不续期会话。

2026-11-06验证：全部162项测试通过，新增真实HTTP当前用户查询、账号隔离、资料/角色更新、
账号限制、过期/退出凭证拒绝、响应字段白名单及会话不续期验证；注册、登录、退出和两种改密流程回归通过。

## 修改当前用户资料

`PATCH /api/v1/me`需要Bearer身份，提交GET /me返回的资料version作为expectedVersion，
并至少提供displayName、bio、avatarAssetId中的一个字段。只更新提供的字段，成功200并返回写入后的CurrentUser及新version。
不接收客户端指定的userId或邮箱，不允许回传roles、accountStatus、emailVerified、密码等字段；未知字段返回400。

| 字段 | 省略 | 显式null | 其他输入 |
| --- | --- | --- | --- |
| displayName | 保持原值 | 422 | 非空昵称，1～40个Unicode字符 |
| bio | 保持原值 | 422 | 最多500个Unicode字符，空字符串清空简介 |
| avatarAssetId | 保持原值 | 清空头像 | 本期尚不开放非空ID绑定，返回503 |

DTO通过JSON Setter保留字段存在标记，拒绝客户端篡改内部标记，不将数字、数组或对象静默转换为资料字符串。
昵称和简介使用Unicode码点计数，不静默截断或trim。只提交expectedVersion或不符合字段校验的输入返回422。

UserProfileService与事务、DTO、配置、限流和校验都位于user模块。
事务锁定目标账号并再次检查认证版本、ACTIVE状态及资料版本，然后通过MyBatis if/set生成动态更新，
SQL列由后端白名单固定，值使用参数绑定；WHERE包含用户ID、expectedVersion、认证版本及ACTIVE状态。
版本冲突返回409 RESOURCE_VERSION_CONFLICT及currentVersion，客户端重新读取并确认后再保存，不自动覆盖。
对本期已支持且通过校验的字段，值均相同时返回200和当前资料，version与updated_at不变；过时版本仍返回409。

实际修改只递增资料version，不更改auth_version、密码、角色、邮箱验证状态和会话，不要求重新登录。
接口返回数据库更新后的资料，而非认证阶段的旧CurrentUser快照。WRITE_BLOCKED或DISABLED不能修改资料。
认证状态在过滤器验证后发生变化时，事务复核会拒绝写入；依赖故障返回503，响应仍遵循Problem Details及no-store规范。

资料编辑阶段先支持清空头像；后续新增的素材登记及创建上传会话见下文。avatarAssetId:null和省略头像均可正常处理；
所有非空头像ID请求返回503 DEPENDENCY_UNAVAILABLE，包括同时提交昵称/简介的请求，整次修改不发生部分保存。
后续素材模块就绪后，再接入头像归属本人、AVATAR用途与READY状态的校验，不能绕过这些条件直接绑定ID。

app.user.profile-update配置：同来源每分钟30次、同用户每小时20次，独立{profile-update}配额键。
环境变量为PROFILE_UPDATE_IP_PER_MINUTE_LIMIT、PROFILE_UPDATE_USER_HOURLY_LIMIT和PROFILE_UPDATE_REDIS_KEY_PREFIX，
默认Redis前缀fc:user:。所有进入业务的有效请求均计数，包含相同值保存及版本冲突；超限429附Retry-After。
来源使用TCP对端地址，仅当对端属于可信代理网段时采用X-Forwarded-For。

2026-11-06验证：全部183项测试通过，覆盖真实HTTP/MySQL/Redis的部分更新、空值语义、Unicode边界、
SQL参数绑定、相同值不增版本、并发版本冲突、事务内身份/状态复核、头像请求不部分保存、提交限流及API文档字段约束。

## 获取当前上传限制与签名策略

`GET /api/v1/media/upload-policy`需要有效Bearer身份，无请求体，返回200及`ApiResponse<UploadPolicy>`。
接口属于`media`模块，Controller依赖UploadPolicyService接口，由service.impl中的实现读取配置并构造独立响应DTO。
策略读取不访问对象存储，不创建上传会话，不修改账号资料或延长登录会话；认证仍需查询Redis及MySQL。
WRITE_BLOCKED允许查询；缺少或失效身份返回401，DISABLED返回403，认证依赖不可用返回503。
成功及失败响应继续使用统一requestId和no-store规范，不返回桶名、内部存储地址、密钥或签名URL。

配置集中在`app.media.upload-policy`，启动时校验数值范围，并保留不可变的MIME类型列表：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| max-image-bytes | MEDIA_MAX_IMAGE_BYTES | 10485760（10 MiB） |
| max-video-bytes | MEDIA_MAX_VIDEO_BYTES | 524288000（500 MiB） |
| allowed-image-mime-types | MEDIA_ALLOWED_IMAGE_MIME_TYPES | image/jpeg,image/png,image/webp,image/gif |
| allowed-video-mime-types | MEDIA_ALLOWED_VIDEO_MIME_TYPES | video/mp4 |
| multipart-threshold-bytes | MEDIA_MULTIPART_THRESHOLD_BYTES | 16777216（16 MiB） |
| max-part-count | MEDIA_MAX_PART_COUNT | 10000 |
| upload-url-ttl-seconds | MEDIA_UPLOAD_URL_TTL_SECONDS | 600（10分钟） |

环境变量中的MIME列表以逗号分隔。配置在应用启动时绑定，修改后需重启后端。
图片和视频大小范围为1～9007199254740991字节，分片阈值及签名秒数为正整数，分片数范围为1～10000。
原先app.storage下未绑定的图片大小、类型及签名时长预留配置已迁入media；app.storage继续保存存储连接参数。

该节描述策略查询；私有上传会话创建见下节。返回视频、分片参数不代表视频或分片上传接口已经实现。
创建会话已复用这组配置；后续完成上传还须核验实际大小、类型、归属和READY状态，前端检查不能替代服务端检查。
非空头像绑定仍等待素材模块，不因本接口上线而开放。

专项检查覆盖配置覆盖、数值范围、认证状态、响应字段白名单、真实HTTP/MySQL/Redis及生成的OpenAPI必填字段和范围。

2026-11-07验证：`mvn clean "-Dtest=*Test,*Tests,*IT" package`通过全部199项测试（新增16项），
0失败、0错误、0跳过，打包成功。按TDD验证了缺失路由404、非法数值配置、列表可变性及OpenAPI约束缺失，修复后回归通过。

## 创建私有文件上传会话

`POST /api/v1/media/uploads`需要Bearer身份及16～128字符的`Idempotency-Key`，成功201，
返回`ApiResponse<UploadSession>`和`Location: /api/v1/media/uploads/{uploadId}`。
请求接收scene、fileName、contentType、整数sizeBytes及可选的64位十六进制sha256。
用户取认证上下文，来源取可信代理后的客户端地址，不接受ownerId、存储路径或存储桶等额外字段。

当前按P0只接受AVATAR、CONTENT_IMAGE、REPORT_ATTACHMENT用途的策略白名单图片，默认最大10 MiB。
CONTENT_VIDEO、非白名单MIME及需要分片的申请返回415，图片超限返回413。
MIME去首尾空白并转小写，sha256转小写，文件名仅保存显示值；数值字符串、小数及错误字段类型拒绝，避免隐式转换。
缺失必填请求头返回400 MALFORMED_REQUEST，幂等键长度错误及字段约束错误返回422。

业务配置为`app.media.upload-creation`：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| session-ttl-seconds | MEDIA_UPLOAD_SESSION_TTL_SECONDS | 7200（2小时） |
| ip-per-minute-limit | MEDIA_UPLOAD_IP_PER_MINUTE_LIMIT | 60 |
| user-hourly-limit | MEDIA_UPLOAD_USER_HOURLY_LIMIT | 100 |
| redis-key-prefix | MEDIA_UPLOAD_REDIS_KEY_PREFIX | fc:media: |

上传签名沿用app.media.upload-policy.upload-url-ttl-seconds，默认600秒，且不会超过会话有效期。
Redis Lua原子检查用户小时窗口及TCP来源分钟窗口；拒绝时两个计数都不增加，429返回Retry-After。
窗口从各计数首次获准申请时起算。
认领后确认是新请求才计数；重放和同键不同请求直接返回，不消耗额度。新请求的策略拒绝及后续数据库失败不自动退还额度。
仅在TCP对端属于可信代理网段时采用X-Forwarded-For；可信网段由TRUSTED_PROXY_PATTERN配置。

### 持久化、并发及故障恢复

V5迁移新增media_asset、media_upload_session和media_upload_request，保留V1～V4及既有账号数据。
素材保存声明大小、声明类型及可选摘要，初始WAITING_UPLOAD，实际校验字段尚为空；上传会话初始UPLOADING、version=1。
素材ID、上传ID及private/{userId}/{assetId}/source对象键均由服务端分配，不使用原始文件名作为路径。

处理顺序：检查请求及限流 → 认领幂等记录并提交短事务 → 检查当前文件策略并准备签名 → 最终写事务 → 返回201。
两个事务都锁定并复核账号authVersion、ACTIVE及邮箱验证状态，按账号行再幂等记录的顺序加锁。
最终事务把素材、上传会话和幂等成功结果一起提交；任一数据库写入失败整体回滚。
数据库和Redis不构成分布式事务，签名准备不占用数据库写事务。

幂等作用域为用户＋createUpload＋键，24小时有效，数据库保存键摘要和规范化请求指纹。
同键同请求重放原资源、状态、Location及签名快照，且不消耗限流额度；同键不同请求返回409 IDEMPOTENCY_KEY_REUSED。
处理中返回409 REQUEST_IN_PROGRESS和Retry-After。处理租约60秒，过期后重试可接管，最终提交复核租约令牌，防止旧请求覆盖新结果。
准备失败只释放本次持有的PROCESSING记录；数据库故障无法释放时，后续重试依靠租约恢复。
24小时过期后允许重新使用该键创建新资源，原资源保留。过期幂等记录按请求惰性回收，全量清理随后续后台清理流程完善。

### 签名与实际文件上传

UploadSigner使用既有MinIO SDK的SigV4实现，固定创建时的签名时间、有效期、对象、Content-Type和Content-Length；请求提供sha256时还绑定x-amz-checksum-sha256，支持重建相同结果。
Maven显式补齐与MinIO 9.0.4匹配的okhttp-jvm 5.5.1；多平台入口包本身不包含JVM HTTP实现。
存储配置继续使用app.storage及既有MINIO_*变量，新增MINIO_REGION（默认us-east-1）。
数据库只保存签名参数及配置指纹，不保存完整签名URL或存储密钥；凭证类和上传目标的toString已脱敏。
更换存储地址、桶、区域或密钥后，旧签名快照不能用新配置静默重建，重放会返回503。

生成签名是本地计算，不逐次探测MinIO在线状态；创建成功表示业务记录已经提交，实际文件传输由客户端执行。
前端按singleUploadTarget的PUT、URL和headers发送文件二进制（必须带Content-Type、Content-Length；提供sha256时还要带x-amz-checksum-sha256），不附带业务Bearer。
签名约束方法、对象、Content-Type及Content-Length，实际大小不符会在写入前被MinIO拒绝；提供sha256时校验和也会被验证。真实格式及后续READY校验仍由完成上传流程负责。
签名过期与会话过期独立，幂等重放保留原签名到期时间；会话查询接口已支持在会话有效时重新签发地址。

当前已实现创建会话、私有素材登记、查询续签及取消。完成、异步校验及READY流程仍待实现，非空头像绑定继续按原规则拒绝。

2026-11-07验证：`mvn verify`通过全部239项测试（145 单元/Web + 94 集成），
0失败、0错误、0跳过。覆盖真实HTTP/MySQL/Redis/MinIO上传、签名篡改拒绝、私有读取拒绝、
幂等重放/冲突、重放不消耗限流额度、受信代理转发头来源、Content-Length与可选SHA-256校验和、
8并发去重、处理中409、租约接管及旧请求隔离、事务回滚、认证状态复核、限流、严格类型与生成的OpenAPI。

## 查询本人上传进度并恢复上传

`GET /api/v1/media/uploads/{uploadId}`需要Bearer身份，无请求体和Idempotency-Key，成功200及`ApiResponse<UploadSession>`。
只查询本人会话，非本人或不存在统一404；非法ID返回422及path位置的uploadId校验信息。
返回数据库当前version及原id、assetId、expiresAt，不创建新资源、不递增版本、不延长会话或登录有效期。
本期只支持SINGLE，因此partSizeBytes为null、totalParts为1、uploadedParts为[]；不虚构已上传字节数或百分比。

UPLOADING且仍有效、账号ACTIVE时，沿用原storageKey、Content-Type、声明大小及可选SHA-256生成新签名。
签名有效期取当前策略、会话剩余整秒和S3最大604800秒中的最小值，剩余不足一个签名秒时不再签发。
签名参数通过内存副本计算，不覆盖创建时的signature_at、signature_ttl_seconds及signer_id，创建接口仍能重放原结果。
SINGLE中断后使用同一个目标重新传完整文件；浏览器丢失File对象时仍需重新选择原文件。

COMPLETED、CANCELLED、EXPIRED保留其终态且singleUploadTarget为null。
UPLOADING记录已超过expiresAt时，响应视图显示EXPIRED，但GET不回写数据库到期状态。
WRITE_BLOCKED允许查询元数据，返回singleUploadTarget:null；DISABLED返回403，认证版本过时返回401。
鉴权通过后，查询事务仍复核当前账号状态和authVersion，防止旧身份继续申请新凭证。

配置位于`app.media.upload-query`，与创建额度独立；通过格式校验的已认证查询均计数，包括终态和404查询。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | MEDIA_UPLOAD_QUERY_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | MEDIA_UPLOAD_QUERY_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | MEDIA_UPLOAD_QUERY_REDIS_KEY_PREFIX | fc:media: |

Redis Lua原子检查两个分钟窗口，拒绝时不消耗另一项配额；429返回Retry-After，Redis不可确认时返回503。
来源取服务端识别的TCP/可信代理地址。窗口从首次获准请求起算，修改配置需重启后端。
Redis配额在SQL事务外检查；查询事务按账号→上传会话顺序取得FOR SHARE锁，使用同一数据源复核权限和当前状态。
共享锁允许并发查询，并与账号限制或会话状态的并发修改协调；事务只查询和本地签名，不写业务表、不调用MinIO网络接口。
已经签发的存储地址仍有短期有效窗口，不能撤销已开始的存储传输；后续完成上传仍须复核会话状态和真实文件。

续签后的地址已通过真实MinIO PUT验证，保留Content-Type、Content-Length和可选校验和约束。
前端向MinIO上传时不附带业务Bearer；浏览器用原文件二进制发送，由浏览器生成Content-Length。
singleUploadTarget的OpenAPI 3.1声明已明确允许对象或null；DTO中已有业务注释保留。
查询接口复用V5表结构；取消、后台回收及完成确认见下节。后台媒体校验、素材READY及分片续传仍按后续阶段实现。

2026-11-07验证：`mvn clean verify`通过全部266项测试（152项单元/Web、114项集成），0失败、0错误、0跳过，打包成功。
本用例新增26项专项检查，覆盖原会话续签、期限裁剪、终态/过期、越权及账号限制、独立配额、并发共享锁、
认证后状态复核、原始幂等结果不变、真实MinIO恢复上传及长度/校验和约束、配置校验和OpenAPI 3.1可空模型。

## 取消本人上传与后台回收

`DELETE /api/v1/media/uploads/{uploadId}?expectedVersion=当前版本`需要Bearer身份，无JSON请求体和幂等键。
成功204且响应体为空，表示取消状态与持久化清理任务已经提交，不表示MinIO文件已物理删除。
客户端停止发送文件需另外调用XHR.abort或AbortController.abort，不能用本地中断代替服务端204确认。

UploadCancellationService及其实现归media模块。事务按账号→会话→素材加锁，复核authVersion、ACTIVE状态及本人归属。
首次取消仅允许未到期UPLOADING、expectedVersion匹配且素材WAITING_UPLOAD；条件更新会话为CANCELLED、version+1，
将素材设为已有枚举FAILED，并在同一事务插入唯一清理任务。任务入队失败时所有更新回滚，API返回503。
已经CANCELLED的本人会话重复取消返回204，包括携带第一次的旧正整数版本，不增加版本或重复创建任务。
COMPLETED、EXPIRED、超时UPLOADING、版本冲突及已READY/PROCESSING素材均返回409 UPLOAD_STATE_CONFLICT。
非法ID或非正版本返回422，缺少、无法解析或超出int32范围的版本返回400；非本人或不存在统一404。
WRITE_BLOCKED及DISABLED禁止取消；取消不改变账号资料版本、认证版本或其他会话。

创建接口的幂等重放也复核原上传状态与期限，取消、完成或过期后不再返回原上传凭证，避免借旧创建键继续操作。
此类终态重放使用创建接口契约已有的403 PERMISSION_DENIED；取消接口自身的状态和版本冲突仍为409 UPLOAD_STATE_CONFLICT。
查询接口对CANCELLED返回singleUploadTarget:null。完成上传接口同样拒绝CANCELLED。

配置为`app.media.upload-cancel`，计数窗口和创建、查询独立，所有合法取消请求及重复取消均计数。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | MEDIA_UPLOAD_CANCEL_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | MEDIA_UPLOAD_CANCEL_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | MEDIA_UPLOAD_CANCEL_REDIS_KEY_PREFIX | fc:media: |

复用双分钟配额Lua算法，使用独立{upload-cancel}键作用域，429附Retry-After。计数在SQL事务外完成，不因后续事务失败退还。

### 持久化清理任务

V6新增media_upload_cleanup，按upload_id唯一，保存对象键、存储配置指纹、计划时间、尝试次数、租约令牌及安全错误码。
原V1～V5保持不变。任务首次执行时间为原会话expiresAt＋grace-seconds（默认600秒），覆盖已签发地址的有效窗口并预留在途缓冲。
UploadCleanupWorker每轮最多认领一个任务，原子更新为RUNNING；存储删除在数据库事务外进行。
执行前复核会话CANCELLED、素材FAILED、三方归属/关联/对象键及存储配置一致，限定生成的私有对象前缀，禁止误删已就绪或其他对象。
删除使用既有MinIO SDK，调用总超时10秒；不存在的对象可幂等删除，进程在删除后、回写前退出时可由过期租约恢复。
结果回写同时校验RUNNING、租约令牌及租约期限，旧执行者不能覆盖新认领结果。

配置为`app.media.upload-cleanup`：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| enabled | MEDIA_UPLOAD_CLEANUP_ENABLED | true |
| grace-seconds | MEDIA_UPLOAD_CLEANUP_GRACE_SECONDS | 600 |
| poll-delay-millis | MEDIA_UPLOAD_CLEANUP_POLL_DELAY_MILLIS | 1000 |
| lease-seconds | MEDIA_UPLOAD_CLEANUP_LEASE_SECONDS | 60，必须大于存储调用10秒上限 |
| max-attempts | MEDIA_UPLOAD_CLEANUP_MAX_ATTEMPTS | 3 |
| retry-base-seconds | MEDIA_UPLOAD_CLEANUP_RETRY_BASE_SECONDS | 5，指数退避且最多60秒 |

失败时重新排队，达到尝试上限保留FAILED；配置变化或资源状态不再允许删除时也保留失败记录，不对外暴露存储内部信息。
物理清理失败不会撤回已提交的取消结果，后台故障由任务记录和安全日志排查。当前没有公开的清理任务查询或重试接口。
取消不直接关闭浏览器到MinIO的连接；极长在途传输不受数据库状态强制中断，缓冲并非网络截止保证，后续孤立对象对账需覆盖此类残留。
后台媒体校验、READY及分片上传/AbortMultipartUpload仍是后续阶段；完成确认与媒体任务查询见下节，前端上传控件未修改。

2026-11-07验证：`mvn clean verify`通过全部306项测试（170项单元/Web、136项集成），0失败、0错误、0跳过，打包成功。
本用例新增40项专项检查，覆盖204空响应、重复取消、状态/版本/归属保护、旧创建键凭证拒绝、入队失败回滚、
认证版本复核、取消与完成条件更新竞争、独立限流、真实MinIO延迟回收、持久化重试、并发认领、租约接管及旧令牌回写拒绝。

## 确认上传完成与媒体任务查询

| 接口 | 请求 | 成功结果 |
| --- | --- | --- |
| `POST /api/v1/media/uploads/{uploadId}/completion` | Bearer、Idempotency-Key及CompleteUploadRequest | 202、TaskReceipt、Location和Retry-After |
| `GET /api/v1/tasks/{taskId}` | Bearer，无请求体 | 200、本人MEDIA_PROCESSING任务 |

当前只支持P0图片SINGLE，完成请求示例：

```json
{"expectedVersion": 1}
```

SINGLE必须省略parts，显式null或提交分片清单均返回422；视频和分片完成尚未实现。
版本必须是1～2147483647的JSON整数；错误类型或未知字段返回400，缺失/非正版本返回422。
路径ID和幂等键分别按契约限制校验；幂等键长度为16～128字符。

### 存储检查与原子提交

服务先复核本人归属、当前认证版本、账号写权限、会话期限/版本及WAITING_UPLOAD素材，
保存60秒租约的完成请求预留记录，再在数据库事务外向MinIO执行HEAD检查。
对象不存在或实际大小与声明不符返回409 UPLOAD_STATE_CONFLICT，保留UPLOADING，允许补传后用原键重试；
存储Content-Type与声明不符返回415，存储/Redis/数据库不可用返回503。

检查通过后按账号→幂等记录→会话→素材的锁顺序再次复核身份、期限和源信息，
在同一事务中更新会话COMPLETED及version+1、素材PROCESSING、PENDING媒体任务和成功幂等结果。
任务入库失败则整个事务回滚。取消若先提交，完成返回409且不留下媒体处理任务；完成若先提交，取消返回409。
原会话expiresAt、对象路径和创建签名快照不变，完成后的会话查询不再签发上传凭证。

V7新增`media_processing_task`及`media_upload_completion_request`，V1～V6保持不变。
任务记录服务端获得的对象ETag、版本号、实际大小、Content-Type、修改时间及预期SHA-256，
不保存完整签名URL，查询DTO也不暴露这些内部存储字段。

HEAD只能核对存储元数据。实际文件格式、内容SHA-256和图片尺寸等仍须由后续worker检查。
旧上传签名在原有效期内仍可能覆盖源对象，后续worker必须按记录的版本/对象快照读取并复核，
将最终可用文件固定到受保护的版本或独立存储键后才可设为READY，不能把本次HEAD结果当作完整内容校验。

### 幂等、任务与限流

完成键作用域为调用者＋completeMyUpload＋key，保留24小时，指纹包含uploadId和expectedVersion。
同键同请求重放原TaskReceipt及Location，不重复创建任务；即使任务状态随后变化，回执仍保留原PENDING受理结果。
同键不同目标或版本返回409 IDEMPOTENCY_KEY_REUSED；处理中返回409 REQUEST_IN_PROGRESS及Retry-After。
租约过期可恢复，旧租约持有者不能提交或清除新持有者的结果。新键再次完成已COMPLETED会话返回409。

完成接口采用独立分钟配额：同来源120次、同用户60次，成功幂等重放也计数；不因后续失败退还额度。
配置为`app.media.upload-completion`，Retry-After默认2秒。
任务查询采用`app.media.task-query`的独立来源120次/分钟、用户60次/分钟配额，与其他上传接口分别计数。
创建接口既有“成功幂等重放免计创建配额”规则保持不变。

TaskReceipt.statusUrl和Location均为`/api/v1/tasks/{taskId}`，可直接查询本人的MEDIA_PROCESSING任务。
其他用户、未知任务和内部邮件/清理任务ID均返回404；WRITE_BLOCKED可以读取任务，不能提交完成确认。
任务查询返回契约要求的状态、进度、错误字段及时间，不返回存储路径、凭证或内部任务记录。

本阶段没有后台媒体处理worker，任务保持PENDING、progressPercent=0、retryable=false。
202只表示持久化受理。素材查询已实现；任务执行、实际文件校验、READY推进、失败任务重试及头像绑定后续实现。

专项验证覆盖真实HTTP/MySQL/Redis/MinIO上传与HEAD、原子提交和回滚、所有权、权限变化、
状态/版本/期限、幂等重放和指纹冲突、独立限流、并发完成/取消、租约恢复及生成的OpenAPI。
全量验证日志见`.local-dev/complete-upload-full-check.log`。

2026-11-07本阶段验证：`mvn clean verify`通过385项测试（214项单元/Web、171项集成），
0失败、0错误、0跳过，打包成功；本业务新增59项专项检查，V7迁移已执行。

## 查询本人的媒体处理任务

`GET /api/v1/tasks/{taskId}`需要Bearer，无请求体和Idempotency-Key，成功200及`ApiResponse<Task>`。
taskId取完成上传返回的TaskReceipt.taskId；不使用uploadId、assetId或客户端指定的ownerId代替当前账号。

调用链为Bearer认证→MediaTaskController→MediaTaskQueryService→独立查询配额→MediaTaskQueryTransactionService。
事务读取当前账号并取得共享锁，复核authVersion及账号限制，再通过Mapper同时限定任务ID、当前ownerId和MEDIA_PROCESSING类型。
其他用户、未知任务及内部清理任务ID统一404；WRITE_BLOCKED可读，DISABLED不可读，认证后账号变化也会重新判断。

查询直接读取当前数据库状态，支持PENDING、RUNNING、SUCCEEDED、FAILED和CANCELLED，
进度、重试关联和公开错误字段均取持久化记录；不会访问MinIO、创建重试任务或改写任务/素材状态及时间。
返回字段严格由MediaTaskView构造，不序列化存储键、对象快照、签名配置或其他内部字段。
正常响应包含X-Request-Id及Cache-Control:no-store，401包含WWW-Authenticate，429包含正整数Retry-After；Swagger同步声明。

`app.media.task-query`默认同来源120次/分钟、同用户60次/分钟，配置及环境变量已在application.yml和infra/.env.example列出。
已认证且ID格式有效的查询均计数，包含404、终态和重复查询；Redis结果无法确认或数据库读取失败返回503。

当前没有媒体处理worker，新完成任务仍保持PENDING。查询支持五种状态不代表后台校验、转码或READY推进已实现。
MediaTaskQueryFlowIT使用受控数据库记录验证各状态、实时读取、查询不改数据、权限复核、任务隔离、HTTP错误及响应头。
MediaTaskQueryRateLimiterTest验证Redis故障和不确定结果，完整回归日志为`.local-dev/media-task-query-full-check.log`。

2026-11-07独立验收通过新增21项任务查询检查。`mvn clean verify`通过406项测试
（216项单元/Web、190项集成），0失败、0错误、0跳过，打包成功；本轮复用V7，无新增迁移。

## 查询本人素材校验结果

`GET /api/v1/media/assets/{assetId}`需要Bearer，无请求体和Idempotency-Key，成功200及`ApiResponse<Asset>`。
使用创建上传会话或完成确认回执中的真实assetId，不使用uploadId或taskId代替。

接口从认证上下文取得当前账号，按账号共享锁复核authVersion、账号限制及权限，
再同时限定assetId和ownerId查询素材。其他用户或未知素材统一404；WRITE_BLOCKED可读，DISABLED不可读。
返回id、ownerId、scene、state、contentType、sizeBytes、durationSeconds、width、height和createdAt，
不返回文件地址、存储键、声明文件名、SHA-256、签名参数或存储凭证。

状态按数据库如实返回WAITING_UPLOAD、PROCESSING、READY或FAILED。
实际MIME尚未知时contentType暂用创建时的声明值，实际大小尚未知时sizeBytes为0；
有实际校验值后优先使用实际值，声明大小不会被用来冒充实际大小。前端必须结合state判断是否已完成校验。
宽高没有结果时返回null；非视频durationSeconds始终为null，视频时长读取已存储的秒数。
READY记录若缺少实际MIME或实际大小，返回503 DEPENDENCY_UNAVAILABLE，不伪造READY元数据。

查询只读取持久化元数据，不访问MinIO、不计算尺寸或校验和、不推进状态，也不修改素材记录。
V8复用V5已有actual_content_type和actual_size_bytes，新增可空duration_seconds、width及height；
时长使用DECIMAL(20,6)，时长非负、宽高为正数的约束由数据库保证，V1～V7保持不变。
当前没有媒体校验worker，正常完成确认后的素材仍为PROCESSING，实际元数据由后续worker填写。
读取受控测试记录中的READY/视频元数据，不代表当前已实现媒体处理或视频上传。

独立配置`app.media.asset-query`默认来源120次/分钟、用户60次/分钟。
已认证且ID格式有效的重复读取、404和终态查询均计数，与任务查询及其他上传接口分别计数。
429附正整数Retry-After；Redis故障或结果无法确认、数据库读取失败均返回503。
所有响应包含X-Request-Id及Cache-Control:no-store，401包含WWW-Authenticate，Swagger声明同步。

专项检查覆盖元数据优先级、四种状态、空值、64位大小和精确时长、权限变化、查询不改数据、独立限流、
数据库/Redis故障、OpenAPI及真实“创建→MinIO直传→完成确认→素材查询”链路。
全量验证日志为`.local-dev/media-asset-query-full-check.log`。

2026-11-07验证：本业务新增34项检查；`mvn clean verify`通过440项测试
（223项单元/Web、217项集成），0失败、0错误、0跳过，打包成功；V8迁移已执行。
