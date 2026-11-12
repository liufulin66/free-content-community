# 认证与会话

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### 邮箱验证码接口

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

### 邮箱验证码注册

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

### 密码登录与Redis会话

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

### 退出当前会话

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

### 验证码重置密码

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

## 调用链阅读顺序

### 发送验证码怎样阅读

按顺序打开：

1. [AuthController](../src/main/java/com/freecontent/community/auth/controller/AuthController.java)的createEmailChallenge方法：
   HTTP路径为`POST /api/v1/auth/email-challenges`。
2. [EmailChallengeService](../src/main/java/com/freecontent/community/auth/service/EmailChallengeService.java)声明业务方法；
   [EmailChallengeServiceImpl](../src/main/java/com/freecontent/community/auth/service/impl/EmailChallengeServiceImpl.java)
   规范化邮箱、检查限流、生成验证码、计算摘要及密文。
3. [EmailChallengeTransactionService](../src/main/java/com/freecontent/community/auth/service/transaction/EmailChallengeTransactionService.java)：
   锁定旧申请、处理重发、同事务保存新申请与邮件任务。
4. [EmailDeliveryWorker](../src/main/java/com/freecontent/community/auth/job/EmailDeliveryWorker.java)：
   后台认领并投递已保存任务，按租约令牌回写结果。

前三步完成后返回202；后台投递独立执行。SMTP操作不占用长时间数据库事务。
SMTP连接、读取、写入超时分别默认3秒、5秒、5秒，配置位于`spring.mail.properties`；
`auth/job/EmailDeliveryWorkerTest`通过真实但不响应的本机SMTP连接验证超时后进入重试。

### 邮箱注册怎样阅读

按顺序打开：

1. [AuthController](../src/main/java/com/freecontent/community/auth/controller/AuthController.java)的register方法：
   HTTP路径为`POST /api/v1/auth/registrations`。
2. [RegistrationService](../src/main/java/com/freecontent/community/auth/service/RegistrationService.java)声明业务方法；
   [RegistrationServiceImpl](../src/main/java/com/freecontent/community/auth/service/impl/RegistrationServiceImpl.java)
   限流、预检查验证码、计算密码哈希，再调用最终注册事务。
3. [RegistrationVerificationService](../src/main/java/com/freecontent/community/auth/service/transaction/RegistrationVerificationService.java)：
   固定REGISTER用途，委托[EmailChallengeVerificationService](../src/main/java/com/freecontent/community/auth/service/transaction/EmailChallengeVerificationService.java)
   校验邮箱、用途、状态及有效期，维护错码次数和锁定状态。
4. [PasswordHasher](../src/main/java/com/freecontent/community/auth/security/PasswordHasher.java)：
   限制密码计算并发，在写事务之外计算Argon2id哈希。
5. [RegistrationTransactionService](../src/main/java/com/freecontent/community/auth/service/transaction/RegistrationTransactionService.java)：
   在同一事务中复核验证码、创建账号、授予基础角色、消费验证码并取消旧投递。

#### 为什么验证码检查有两个调用位置

预检查避免无效验证码触发高成本密码计算。密码计算结束后，验证码可能已经过期、
被重发替代或被其他请求消费，所以写事务中必须再次锁定和复核。

预检查单独执行时，错码返回false，让失败计数先提交，之后由主Service返回400。
写事务调用同一个校验组件时，它加入已有事务，和账号创建共享提交及回滚边界。
这种边界由不同Spring Bean之间的代理调用建立，整理目录时保留原有事务注解及调用方式。

### 密码登录怎样阅读

1. [AuthController](../src/main/java/com/freecontent/community/auth/controller/AuthController.java)的login方法：
   `POST /api/v1/auth/sessions`，独立LoginRequest校验，返回201及LoginResult。
2. [LoginService](../src/main/java/com/freecontent/community/auth/service/LoginService.java)声明业务方法；
   [LoginServiceImpl](../src/main/java/com/freecontent/community/auth/service/impl/LoginServiceImpl.java)
   邮箱规范化、限流、密码校验、签发前复核账号和认证版本。
3. [PasswordHasher](../src/main/java/com/freecontent/community/auth/security/PasswordHasher.java)：
   encode、matches和未知邮箱虚拟校验共享计算并发上限。
4. [RedisSessionStore](../src/main/java/com/freecontent/community/auth/session/RedisSessionStore.java)：
   安全随机Token，Redis只保存摘要；Lua原子维护用户索引和最多5个会话。
5. [SecurityConfiguration](../src/main/java/com/freecontent/community/auth/config/SecurityConfiguration.java)：
   无HttpSession的Spring Security链；仅通过Bearer请求头认证，错误继续使用Problem Details。
6. [SessionAuthenticationService](../src/main/java/com/freecontent/community/auth/service/SessionAuthenticationService.java)：
   后续请求读取Redis会话，并从MySQL核对authVersion、当前账号状态和角色。

会话固定24小时，每次密码登录成功独立计算expiresAt。读取只更新lastSeenAt，不续期。
auth_version独立于资料version；验证码重置和已登录改密均已在事务内递增，后续禁用账号同样必须递增它。
版本失配拒绝旧会话，不依赖Redis物理删除及时完成。请求已通过认证后的并发业务写入，
仍需在相应业务事务中检查当前状态。

当前已实现登录、退出当前会话、验证码重置、已登录改密、GET /api/v1/me及认证基础；P1会话列表仍待实现。
添加新接口时须依据契约声明公开、可选认证及角色权限，不能统一放行。

### 退出当前会话怎样阅读

1. [AuthController](../src/main/java/com/freecontent/community/auth/controller/AuthController.java)的logout方法：
   `DELETE /api/v1/auth/sessions/current`，无请求体，成功204且无响应体。
2. [SessionAuthenticationService](../src/main/java/com/freecontent/community/auth/service/SessionAuthenticationService.java)：
   校验Bearer会话后，将用户、sessionId和Token摘要放入内部SessionPrincipal，不保存原始Token。
3. [LogoutService](../src/main/java/com/freecontent/community/auth/service/LogoutService.java)声明退出接口；
   [LogoutServiceImpl](../src/main/java/com/freecontent/community/auth/service/impl/LogoutServiceImpl.java)
   仅使用已认证身份定位当前会话。
4. [RedisSessionStore](../src/main/java/com/freecontent/community/auth/session/RedisSessionStore.java)的revoke方法：
   调用[session-revoke.lua](../src/main/resources/redis/session-revoke.lua)，复核会话归属并原子删除会话记录及索引成员。

退出不递增authVersion，不影响其他会话。WRITE_BLOCKED账号允许退出。
已失效Token在认证阶段返回401；认证通过后若并发退出或过期已删除记录，清理索引后仍可完成204。
Redis删除失败或结果无法确认返回503。读取会话不会重新创建已删除的记录；已通过认证的在途请求仍可能完成。

### 验证码重置密码怎样阅读

1. [AuthController](../src/main/java/com/freecontent/community/auth/controller/AuthController.java)的resetPassword方法：
   `POST /api/v1/auth/password-resets`公开入口，输入email、challengeId、code、newPassword，成功204无响应体。
2. [PasswordResetService](../src/main/java/com/freecontent/community/auth/service/PasswordResetService.java)声明业务接口；
   [PasswordResetServiceImpl](../src/main/java/com/freecontent/community/auth/service/impl/PasswordResetServiceImpl.java)
   组织独立限流、验证码预检查、事务外密码哈希、最终写事务及提交后的旧会话清理。
3. [EmailChallengeVerificationService](../src/main/java/com/freecontent/community/auth/service/transaction/EmailChallengeVerificationService.java)：
   重置入口固定PASSWORD_RESET用途，与注册共用邮箱、状态、有效期和验证码摘要校验。
4. [PasswordResetTransactionService](../src/main/java/com/freecontent/community/auth/service/transaction/PasswordResetTransactionService.java)：
   再次锁定校验挑战，锁定账号并更新password_hash和auth_version，消费挑战并取消未完成邮件任务。
5. [session-revoke-before-version.lua](../src/main/resources/redis/session-revoke-before-version.lua)：
   提交后仅清理旧认证版本，保留重置后新登录产生的会话，支持BIGINT版本精确比较。

正确验证码用于不存在的账号时，同样消费并返回204，不创建账号。重置保留账号状态、角色、邮箱验证状态与资料version。
Redis清理失败不改变已提交的重置结果，旧Token因authVersion失配仍无法认证；记录清理失败，由后续登录和TTL清理残留。
错码先提交计数再由事务外返回400；同一挑战并发使用最多一次成功。配置见app.auth.password-reset。
