# 后端结构与阅读指南

后端是按业务模块分包的Spring Boot应用。先按业务寻找`auth`、`user`或`media`，
再按控制层、业务入口、数据访问等职责定位代码。

后续新增或修改代码遵循项目根目录的[业务结构约定](../AGENTS.md)：同一用例的DTO、Service、事务、限流、配置和测试归所属业务模块。

## 1. 顶层模块

路径：`src/main/java/com/freecontent/community/`。

| 包 | 职责 |
| --- | --- |
| `auth` | 验证码、注册、密码登录、退出、验证码重置及Redis会话认证 |
| `user` | 当前账号与用户资料业务、修改当前账号密码、角色及数据库访问 |
| `media` | 上传策略、私有上传会话创建与素材登记 |
| `common` | 统一响应、异常、请求标识、通用校验 |
| `config` | Web、MyBatis、OpenAPI等全局配置 |

`FreeContentApplication` 是启动入口。所有业务包都在其扫描范围内。

## 2. 业务模块内部职责

```text
auth/
├── controller/
│   └── AuthController.java
├── service/
│   ├── EmailChallengeService.java       # 对外业务接口
│   ├── RegistrationService.java         # 对外业务接口
│   ├── LoginService.java                # 对外业务接口
│   ├── LogoutService.java               # 对外业务接口
│   ├── PasswordResetService.java        # 对外业务接口
│   ├── SessionAuthenticationService.java
│   ├── impl/
│   │   ├── EmailChallengeServiceImpl.java
│   │   ├── RegistrationServiceImpl.java
│   │   ├── LoginServiceImpl.java
│   │   ├── LogoutServiceImpl.java
│   │   └── PasswordResetServiceImpl.java
│   └── transaction/
│       ├── EmailChallengeTransactionService.java
│       ├── EmailChallengeVerificationService.java
│       ├── PasswordResetTransactionService.java
│       ├── RegistrationTransactionService.java
│       └── RegistrationVerificationService.java
├── ratelimit/
│   ├── EmailChallengeRateLimiter.java
│   ├── RegistrationRateLimiter.java
│   ├── LoginRateLimiter.java
│   └── PasswordResetRateLimiter.java
├── job/
│   └── EmailDeliveryWorker.java
├── security/
│   ├── EmailChallengeCrypto.java
│   ├── PasswordHasher.java
│   ├── BearerSessionFilter.java
│   ├── SessionPrincipal.java
│   └── TokenDigest.java
├── session/                 # Redis会话存储及签发结果
├── config/
├── dto/
├── entity/
├── mapper/
└── model/
```

当前账号资料查询、编辑与改密的业务代码集中在用户模块：

```text
user/
├── controller/MeController.java
├── dto/
│   ├── ChangePasswordRequest.java
│   ├── CurrentUser.java
│   └── UpdateProfileRequest.java
├── service/
│   ├── CurrentUserService.java
│   ├── PasswordChangeService.java
│   ├── UserProfileService.java
│   ├── impl/
│   │   ├── CurrentUserServiceImpl.java
│   │   ├── PasswordChangeServiceImpl.java
│   │   └── UserProfileServiceImpl.java
│   └── transaction/
│       ├── PasswordChangeTransactionService.java
│       └── ProfileUpdateTransactionService.java
├── ratelimit/
│   ├── PasswordChangeRateLimiter.java
│   └── ProfileUpdateRateLimiter.java
├── config/
│   ├── PasswordChangeConfiguration.java
│   ├── PasswordChangeProperties.java
│   ├── ProfileUpdateConfiguration.java
│   └── ProfileUpdateProperties.java
├── validation/               # PATCH字段存在性及可空性校验
├── entity/
├── mapper/
└── model/
```

| 包 | 内容和边界 |
| --- | --- |
| `controller` | 解析HTTP请求、触发字段校验、调用主业务入口、包装响应 |
| `service` | 所属模块的对外业务接口及内部业务组件；Controller依赖业务接口 |
| `service.impl` | 实现所属模块的业务接口，组织该模块的完整业务流程 |
| `service.transaction` | 业务层内部的事务处理，明确哪些数据库操作一起提交或回滚 |
| `ratelimit` | Redis配额检查及Retry-After计算 |
| `job` | 定时扫描邮件任务、认领、发送、重试和结果回写 |
| `security` | 密码哈希及校验、Bearer认证过滤器、验证码加密工具 |
| `session` | Redis会话签发与查询、用户会话索引及超限淘汰 |
| `config` | `Properties`读取并校验参数；`Configuration`注册Spring Bean |
| `dto` | HTTP请求与响应数据结构 |
| `entity` | 数据库记录映射 |
| `mapper` | 数据库查询、条件更新和插入 |
| `model` | 验证码用途等业务类型 |

验证码、注册、登录、退出、重置密码的HTTP入口统一放在AuthController，对应业务Service接口继续独立。
当前账号路由由user.controller.MeController提供，资料查询、编辑和改密的接口、实现、DTO、事务、限流、配置、校验与测试均归user。
用户模块复用auth.security中的密码工具、认证上下文和auth.session中的会话能力，不把整个改密用例放入auth。
Spring将实现接口且标注@Service的Impl类注册为Bean，Controller通过构造方法依赖接口。
SessionAuthenticationService、事务组件、限流器和安全工具保留具体类。

`service.transaction` 属于业务层内部。HTTP控制层调用主Service，
主Service接口的实现协调限流、密码计算及事务组件。Mapper承接数据库操作。
认证事务会调用`user.mapper`，以便账号、角色与验证码消费共享同一个本地事务。

## 3. 发送验证码怎样阅读

按顺序打开：

1. [AuthController](src/main/java/com/freecontent/community/auth/controller/AuthController.java)的createEmailChallenge方法：
   HTTP路径为`POST /api/v1/auth/email-challenges`。
2. [EmailChallengeService](src/main/java/com/freecontent/community/auth/service/EmailChallengeService.java)声明业务方法；
   [EmailChallengeServiceImpl](src/main/java/com/freecontent/community/auth/service/impl/EmailChallengeServiceImpl.java)
   规范化邮箱、检查限流、生成验证码、计算摘要及密文。
3. [EmailChallengeTransactionService](src/main/java/com/freecontent/community/auth/service/transaction/EmailChallengeTransactionService.java)：
   锁定旧申请、处理重发、同事务保存新申请与邮件任务。
4. [EmailDeliveryWorker](src/main/java/com/freecontent/community/auth/job/EmailDeliveryWorker.java)：
   后台认领并投递已保存任务，按租约令牌回写结果。

前三步完成后返回202；后台投递独立执行。SMTP操作不占用长时间数据库事务。

## 4. 邮箱注册怎样阅读

按顺序打开：

1. [AuthController](src/main/java/com/freecontent/community/auth/controller/AuthController.java)的register方法：
   HTTP路径为`POST /api/v1/auth/registrations`。
2. [RegistrationService](src/main/java/com/freecontent/community/auth/service/RegistrationService.java)声明业务方法；
   [RegistrationServiceImpl](src/main/java/com/freecontent/community/auth/service/impl/RegistrationServiceImpl.java)
   限流、预检查验证码、计算密码哈希，再调用最终注册事务。
3. [RegistrationVerificationService](src/main/java/com/freecontent/community/auth/service/transaction/RegistrationVerificationService.java)：
   固定REGISTER用途，委托[EmailChallengeVerificationService](src/main/java/com/freecontent/community/auth/service/transaction/EmailChallengeVerificationService.java)
   校验邮箱、用途、状态及有效期，维护错码次数和锁定状态。
4. [PasswordHasher](src/main/java/com/freecontent/community/auth/security/PasswordHasher.java)：
   限制密码计算并发，在写事务之外计算Argon2id哈希。
5. [RegistrationTransactionService](src/main/java/com/freecontent/community/auth/service/transaction/RegistrationTransactionService.java)：
   在同一事务中复核验证码、创建账号、授予基础角色、消费验证码并取消旧投递。

### 为什么验证码检查有两个调用位置

预检查避免无效验证码触发高成本密码计算。密码计算结束后，验证码可能已经过期、
被重发替代或被其他请求消费，所以写事务中必须再次锁定和复核。

预检查单独执行时，错码返回false，让失败计数先提交，之后由主Service返回400。
写事务调用同一个校验组件时，它加入已有事务，和账号创建共享提交及回滚边界。
这种边界由不同Spring Bean之间的代理调用建立，整理目录时保留原有事务注解及调用方式。

## 5. 密码登录怎样阅读

1. [AuthController](src/main/java/com/freecontent/community/auth/controller/AuthController.java)的login方法：
   `POST /api/v1/auth/sessions`，独立LoginRequest校验，返回201及LoginResult。
2. [LoginService](src/main/java/com/freecontent/community/auth/service/LoginService.java)声明业务方法；
   [LoginServiceImpl](src/main/java/com/freecontent/community/auth/service/impl/LoginServiceImpl.java)
   邮箱规范化、限流、密码校验、签发前复核账号和认证版本。
3. [PasswordHasher](src/main/java/com/freecontent/community/auth/security/PasswordHasher.java)：
   encode、matches和未知邮箱虚拟校验共享计算并发上限。
4. [RedisSessionStore](src/main/java/com/freecontent/community/auth/session/RedisSessionStore.java)：
   安全随机Token，Redis只保存摘要；Lua原子维护用户索引和最多5个会话。
5. [SecurityConfiguration](src/main/java/com/freecontent/community/auth/config/SecurityConfiguration.java)：
   无HttpSession的Spring Security链；仅通过Bearer请求头认证，错误继续使用Problem Details。
6. [SessionAuthenticationService](src/main/java/com/freecontent/community/auth/service/SessionAuthenticationService.java)：
   后续请求读取Redis会话，并从MySQL核对authVersion、当前账号状态和角色。

会话固定24小时，每次密码登录成功独立计算expiresAt。读取只更新lastSeenAt，不续期。
auth_version独立于资料version；验证码重置和已登录改密均已在事务内递增，后续禁用账号同样必须递增它。
版本失配拒绝旧会话，不依赖Redis物理删除及时完成。请求已通过认证后的并发业务写入，
仍需在相应业务事务中检查当前状态。

当前已实现登录、退出当前会话、验证码重置、已登录改密、GET /api/v1/me及认证基础；P1会话列表仍待实现。
添加新接口时须依据契约声明公开、可选认证及角色权限，不能统一放行。

## 6. 退出当前会话怎样阅读

1. [AuthController](src/main/java/com/freecontent/community/auth/controller/AuthController.java)的logout方法：
   `DELETE /api/v1/auth/sessions/current`，无请求体，成功204且无响应体。
2. [SessionAuthenticationService](src/main/java/com/freecontent/community/auth/service/SessionAuthenticationService.java)：
   校验Bearer会话后，将用户、sessionId和Token摘要放入内部SessionPrincipal，不保存原始Token。
3. [LogoutService](src/main/java/com/freecontent/community/auth/service/LogoutService.java)声明退出接口；
   [LogoutServiceImpl](src/main/java/com/freecontent/community/auth/service/impl/LogoutServiceImpl.java)
   仅使用已认证身份定位当前会话。
4. [RedisSessionStore](src/main/java/com/freecontent/community/auth/session/RedisSessionStore.java)的revoke方法：
   调用[session-revoke.lua](src/main/resources/redis/session-revoke.lua)，复核会话归属并原子删除会话记录及索引成员。

退出不递增authVersion，不影响其他会话。WRITE_BLOCKED账号允许退出。
已失效Token在认证阶段返回401；认证通过后若并发退出或过期已删除记录，清理索引后仍可完成204。
Redis删除失败或结果无法确认返回503。读取会话不会重新创建已删除的记录；已通过认证的在途请求仍可能完成。

## 7. 验证码重置密码怎样阅读

1. [AuthController](src/main/java/com/freecontent/community/auth/controller/AuthController.java)的resetPassword方法：
   `POST /api/v1/auth/password-resets`公开入口，输入email、challengeId、code、newPassword，成功204无响应体。
2. [PasswordResetService](src/main/java/com/freecontent/community/auth/service/PasswordResetService.java)声明业务接口；
   [PasswordResetServiceImpl](src/main/java/com/freecontent/community/auth/service/impl/PasswordResetServiceImpl.java)
   组织独立限流、验证码预检查、事务外密码哈希、最终写事务及提交后的旧会话清理。
3. [EmailChallengeVerificationService](src/main/java/com/freecontent/community/auth/service/transaction/EmailChallengeVerificationService.java)：
   重置入口固定PASSWORD_RESET用途，与注册共用邮箱、状态、有效期和验证码摘要校验。
4. [PasswordResetTransactionService](src/main/java/com/freecontent/community/auth/service/transaction/PasswordResetTransactionService.java)：
   再次锁定校验挑战，锁定账号并更新password_hash和auth_version，消费挑战并取消未完成邮件任务。
5. [session-revoke-before-version.lua](src/main/resources/redis/session-revoke-before-version.lua)：
   提交后仅清理旧认证版本，保留重置后新登录产生的会话，支持BIGINT版本精确比较。

正确验证码用于不存在的账号时，同样消费并返回204，不创建账号。重置保留账号状态、角色、邮箱验证状态与资料version。
Redis清理失败不改变已提交的重置结果，旧Token因authVersion失配仍无法认证；记录清理失败，由后续登录和TTL清理残留。
错码先提交计数再由事务外返回400；同一挑战并发使用最多一次成功。配置见app.auth.password-reset。

## 8. 修改当前账号密码怎样阅读

1. [MeController](src/main/java/com/freecontent/community/user/controller/MeController.java)：
   `PUT /api/v1/me/password`，从认证上下文取得目标账号，请求只接收currentPassword和newPassword，成功204无响应体。
2. [PasswordChangeService](src/main/java/com/freecontent/community/user/service/PasswordChangeService.java)声明接口；
   [PasswordChangeServiceImpl](src/main/java/com/freecontent/community/user/service/impl/PasswordChangeServiceImpl.java)
   按已认证用户及来源独立限流，在事务外校验旧密码并计算新密码哈希。
3. [PasswordChangeTransactionService](src/main/java/com/freecontent/community/user/service/transaction/PasswordChangeTransactionService.java)：
   锁定账号，复核认证版本、密码哈希和账号状态，再通过条件更新原子修改密码和auth_version。
4. 事务提交后复用RedisSessionStore.revokeBeforeVersion撤销旧会话；保留提交后新登录的会话。

SessionPrincipal额外携带认证时的authVersion，防止校验与写入之间发生凭证变更后被旧请求覆盖。
旧密码错误返回401 AUTHENTICATION_FAILED，保持原密码和会话有效；认证版本过时返回401 AUTHENTICATION_REQUIRED。
WRITE_BLOCKED允许改密，DISABLED拒绝访问。成功后全部旧会话失效，包括当前会话；Redis清理失败仍以认证版本保证逻辑失效。
两次新密码确认属于前端交互，不向本接口增加confirmPassword或客户端指定的userId。

改密配置为app.user.password-change，原有PASSWORD_CHANGE_*环境变量和Redis配额键格式保持不变。
对应单元和集成测试位于src/test/java/com/freecontent/community/user/下，与业务模块对应。

## 9. 获取当前用户资料怎样阅读

1. [MeController](src/main/java/com/freecontent/community/user/controller/MeController.java)的getCurrentUser方法：
   `GET /api/v1/me`需要Bearer身份，无请求体和用户ID参数，返回200及ApiResponse<CurrentUser>。
2. [CurrentUserService](src/main/java/com/freecontent/community/user/service/CurrentUserService.java)声明用户业务接口；
   [CurrentUserServiceImpl](src/main/java/com/freecontent/community/user/service/impl/CurrentUserServiceImpl.java)
   复用认证上下文中本次请求的CurrentUser，不重复查询数据库。
3. [SessionAuthenticationService](src/main/java/com/freecontent/community/auth/service/SessionAuthenticationService.java)
   在进入Controller前查询MySQL账号及角色，校验认证版本和状态后构建SessionPrincipal。

返回CurrentUser的契约字段，不直接序列化UserAccount或SessionPrincipal，避免暴露密码哈希、Token摘要和authVersion。
WRITE_BLOCKED可以查询，canCreate=false；DISABLED返回403，无效或已撤销Token返回401，依赖故障返回503。
后续请求重新认证并读取当前资料、角色与状态。查询不修改账号版本，也不延长会话有效期，响应使用no-store。

## 10. 修改当前用户资料怎样阅读

1. [MeController](src/main/java/com/freecontent/community/user/controller/MeController.java)的updateProfile方法：
   `PATCH /api/v1/me`需要Bearer身份，成功200并返回更新后的CurrentUser。
2. [UpdateProfileRequest](src/main/java/com/freecontent/community/user/dto/UpdateProfileRequest.java)：
   以JSON Setter记录字段是否出现，包括显式null；仅接收expectedVersion、displayName、bio、avatarAssetId。
   [ProfileUpdateValidator](src/main/java/com/freecontent/community/user/validation/ProfileUpdateValidator.java)
   拒绝空修改、空昵称及bio:null；Unicode长度使用共用码点校验。
3. [UserProfileService](src/main/java/com/freecontent/community/user/service/UserProfileService.java)及
   [UserProfileServiceImpl](src/main/java/com/freecontent/community/user/service/impl/UserProfileServiceImpl.java)：
   从认证上下文确定目标账号，并执行独立提交限流。
4. [ProfileUpdateTransactionService](src/main/java/com/freecontent/community/user/service/transaction/ProfileUpdateTransactionService.java)：
   锁定账号，复核认证版本、ACTIVE状态及expectedVersion；修改后重新读取账号和角色，不能返回写入前的上下文快照。
5. [UserAccountMapper](src/main/java/com/freecontent/community/user/mapper/UserAccountMapper.java)的updateProfileFields方法：
   以if/set动态生成字段白名单内的SET，值使用参数绑定；WHERE同时限定用户、资料版本、认证版本及ACTIVE状态。

本期支持昵称、简介和清空头像；省略字段保持原值，bio空字符串表示清空简介，avatarAssetId:null表示清空头像。
头像素材校验尚未接入，所有非空头像ID返回503，整个请求不发生部分保存；后续接入本人READY的AVATAR素材校验后再开放绑定。
支持字段通过校验且值未变化时，返回200且不修改version或updated_at；版本过时始终409 RESOURCE_VERSION_CONFLICT，并返回currentVersion。
实际修改递增资料version，不修改authVersion、密码或会话有效期。配置见app.user.profile-update，默认来源30次/分钟、用户20次/小时。

## 11. 获取上传策略怎样阅读

1. `media/controller/MediaController.java`的getUploadPolicy方法：
   `GET /api/v1/media/upload-policy`，需要Bearer身份，返回200及统一响应包装。
2. `media/service/UploadPolicyService.java`声明接口，`media/service/impl/UploadPolicyServiceImpl.java`
   读取当前生效配置，构造只包含契约7个字段的`media/dto/UploadPolicy.java`。
3. `media/config/UploadPolicyProperties.java`绑定`app.media.upload-policy`，在启动时校验范围并复制为不可变列表；
   `UploadPolicyConfiguration`注册配置。存储连接参数仍归app.storage，不进入API响应。
4. `src/test/java/com/freecontent/community/media/`按config、controller、service对应组织配置、Web及真实HTTP集成测试。

本接口不需要Mapper或数据库事务，也不请求MinIO；身份验证继续复用auth中的过滤器及会话能力。
WRITE_BLOCKED可以读取策略。这里只提供策略查询，后续实际上传和素材绑定仍须按相同策略校验。

## 12. 创建上传会话怎样阅读

1. `media/controller/MediaController.java`的createUpload：`POST /api/v1/media/uploads`，需要Bearer及Idempotency-Key，成功201并返回Location。
2. `media/dto/CreateUploadRequest.java`及`media/validation/UploadSizeDeserializer.java`：字段白名单、用途、Unicode长度、严格整数及摘要校验。
3. `media/service/UploadService.java`及`media/service/impl/UploadServiceImpl.java`：主业务入口，依次完成限流、幂等认领、策略检查、签名准备和事务提交。
4. `media/ratelimit/UploadCreationRateLimiter.java`及`redis/upload-creation-rate-limit.lua`：用户小时窗口和TCP来源分钟窗口的原子配额检查。
5. `media/service/transaction/UploadTransactionService.java`：claim先提交60秒租约；complete复核账号和租约令牌，同事务写入素材、会话及成功结果；release只清理自己持有的未完成请求。
6. `media/storage/UploadSigner.java`：复用MinIO SDK SigV4，绑定对象、PUT方法、Content-Type及Content-Length，存在sha256时绑定x-amz-checksum-sha256；按固定时间参数重建幂等响应，不保存完整签名地址。
7. `media/entity/`和`media/mapper/`对应V5新增的media_asset、media_upload_session和media_upload_request。
8. `media/config/UploadCreationProperties.java`绑定app.media.upload-creation；StorageProperties绑定既有app.storage；UploadCreationConfiguration注册配置。

同一账号操作采用账号行→幂等行的锁顺序。准备签名不持有写事务，晚到的旧请求必须通过租约令牌校验才能提交。
会话的签名时间、TTL及配置指纹是创建响应快照，后续续签不能覆盖这些字段来改变原幂等结果。
当前仅P0图片SINGLE；素材初始WAITING_UPLOAD，尚不能用于头像或内容关联。会话查询、完成、取消及READY处理流程仍待实现。

## 13. 资源与测试

- `src/main/resources/application.yml`：环境连接与业务参数。
- `src/main/resources/db/migration/`：Flyway迁移，已执行版本保持不变。
- `src/main/resources/redis/`：Redis限流Lua脚本。
- `src/test/java/`：按生产包结构组织单元测试、Web测试及事务集成测试。

移动包或重命名后先清理旧编译产物，防止旧包的class残留被Spring扫描。
`mvn test` 只运行不依赖中间件的单元/Web测试；`*IT` 由 Failsafe 在 `verify` 阶段执行。
加载本地环境变量并启动中间件后，可以运行：

```powershell
$env:EMAIL_CHALLENGE_DELIVERY_ENABLED = "false"
mvn verify
```

测试关闭邮件后台投递；集成测试只操作各自生成的测试记录。
