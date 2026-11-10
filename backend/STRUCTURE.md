# 后端结构与阅读指南

后端是按业务模块分包的Spring Boot应用。先按业务寻找`auth`、`user`、`media`、`tag`或`content`，
再按控制层、业务入口、数据访问等职责定位代码。

后续新增或修改代码遵循项目根目录的[业务结构约定](../AGENTS.md)：同一用例的DTO、Service、事务、限流、配置和测试归所属业务模块。

## 1. 顶层模块

路径：`src/main/java/com/freecontent/community/`。

| 包 | 职责 |
| --- | --- |
| `auth` | 验证码、注册、密码登录、退出、验证码重置及Redis会话认证 |
| `user` | 当前账号与用户资料业务、修改当前账号密码、角色及数据库访问 |
| `media` | 上传、续签、取消回收、完成确认、任务与素材查询、就绪对象核验及GET签名 |
| `tag` | 公开有效标签；管理员创建、详情、分页、修改与启停；名称唯一性、创建幂等及独立限流 |
| `content` | 内容与首稿创建、本人创作/回收站分页、工作区与草稿、修订媒体预览授权、引用校验、幂等、限流和事务一致性 |
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

全局`config/WebConfig.java`统一配置CORS与JSON类型边界，阻止数字/布尔值转为字符串以及枚举序号转换。
字段内容及空值仍由所属业务模块的DTO校验，不把业务流程放入全局配置。

Controller使用`common/api/ApiErrorResponses.java`声明额外业务错误，通用OpenAPI响应生成逻辑集中在`config/ProblemResponseCustomizer.java`。
生成器复用ErrorCode和ProblemDetailResponse，按接口分别生成错误集合及公共响应头；成功DTO与专有头留在所属Controller。
公开接口必须显式关闭公共错误集合，生成器会拒绝公开接口使用默认公共错误；该注解仅影响文档，实际异常转换仍由common/error/GlobalExceptionHandler负责。
公共403集合仅包含PERMISSION_DENIED和ACCOUNT_RESTRICTED；邮箱未验证的专用错误由登录、创建上传按契约显式声明。
BearerSessionFilter在认证失败边界按方法和路径适配邮箱未验证错误，其他已实现受保护接口返回PERMISSION_DENIED；业务事务仍独立复核邮箱状态，文档注解不参与鉴权。
config/CommonErrorDocumentationIT使用测试资源contracts/api-v1-error-responses.json中的固定契约快照核对所有现有接口的响应状态、错误码及公共响应头，新增接口需同步更新快照。
所有`/api/v1/admin/**`请求统一经`auth/security/AdminAuthorizationManager.java`按方法与路径判定角色，未登记路径默认拒绝。

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
SMTP连接、读取、写入超时分别默认3秒、5秒、5秒，配置位于`spring.mail.properties`；
`auth/job/EmailDeliveryWorkerTest`通过真实但不响应的本机SMTP连接验证超时后进入重试。

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
当前仅P0图片SINGLE；素材初始WAITING_UPLOAD，尚不能用于头像或内容关联。查询续签、取消与完成确认已实现，后台媒体校验及READY处理流程仍待实现。

## 13. 查询上传会话与恢复上传怎样阅读

1. `media/controller/MediaController.java`的getMyUpload：`GET /api/v1/media/uploads/{uploadId}`，无请求体和幂等键，成功200。
2. `media/service/UploadQueryService.java`及`media/service/impl/UploadQueryServiceImpl.java`：路径ID校验、独立查询配额及依赖异常映射。
3. `media/ratelimit/UploadQueryRateLimiter.java`和`redis/upload-query-rate-limit.lua`：用户与来源独立分钟窗口，Redis配额在SQL事务外检查。
4. `media/service/transaction/UploadQueryTransactionService.java`的read：按账号→会话顺序取得共享锁，复核authVersion、当前状态及归属，组装当前UploadSession。
5. 同类renewTarget和signingView：按剩余有效时间裁剪TTL，用内存副本复用原存储键、大小、类型和摘要调用UploadSigner，不覆盖创建时的签名快照。
6. `user/mapper/UserAccountMapper.java`的findByIdForShare及`media/mapper/MediaUploadMapper.java`的findOwnedForShare：只查询并协调并发写入，无业务数据更新。
7. `media/config/UploadQueryProperties.java`及UploadQueryConfiguration：app.media.upload-query配置与启动校验。

查询中的版本和到期时间保持数据库原值；UPLOADING已超时只在响应视图显示EXPIRED。
终态或WRITE_BLOCKED返回singleUploadTarget:null，禁止通过GET申请新的写入凭证。
P0固定SINGLE、partSizeBytes:null、totalParts:1及uploadedParts:[]；分片列表和真正的断点续传后续实现。
本接口沿用V5表结构，不新增迁移；测试按media模块的config、service和ratelimit目录组织。

## 14. 取消上传与文件回收怎样阅读

1. `media/controller/MediaController.java`的cancelMyUpload：DELETE本人uploadId，expectedVersion在查询参数中，成功204空响应。
2. `media/service/UploadCancellationService.java`及`service/impl/UploadCancellationServiceImpl.java`：输入校验、独立取消限流及依赖错误映射。
3. `media/service/transaction/UploadCancellationTransactionService.java`：账号→会话→素材锁序，CAS取消、素材FAILED及清理任务同事务提交。
4. `media/entity/UploadCleanupTask.java`及`media/mapper/UploadCleanupTaskMapper.java`：V6持久化清理记录，upload_id唯一，原子租约认领及令牌回写。
5. `media/job/UploadCleanupWorker.java`：到期后认领一个任务，复核取消状态、素材不可用、归属、对象键和存储配置；删除失败退避，耗尽重试保留FAILED。
6. `media/storage/UploadObjectCleaner.java`：MinIO物理删除及10秒总超时；存储I/O不持有取消事务，Bean关闭时释放HTTP资源。
7. `media/config/UploadCancellationProperties.java`与UploadCleanupProperties：app.media.upload-cancel和app.media.upload-cleanup配置；UploadCancellationConfiguration注册。

后台回收默认等待原会话到期后600秒，204只确认逻辑取消与持久化入队。前端仍需单独abort文件请求。
重复取消不递增版本或重复入队；已完成上传及READY/PROCESSING素材不能通过此接口删除。
创建幂等重放同时检查原会话状态与期限，避免取消后返回旧上传凭证。

## 15. 完成上传与媒体处理任务怎样阅读

1. `media/controller/UploadCompletionController.java`：POST完成确认，接收版本及幂等键，返回202、TaskReceipt、Location和Retry-After。
2. `media/dto/CompleteUploadRequest.java`：严格整数、字段白名单和分片模型；当前SINGLE必须省略parts。
3. `media/service/UploadCompletionService.java`与`service/impl/UploadCompletionServiceImpl.java`：输入校验、独立计数、幂等认领、真实存储检查及事务提交协调。
4. `media/service/transaction/UploadCompletionTransactionService.java`：claim短事务预留60秒租约；finish重新校验账号→幂等记录→会话→素材并原子更新两种状态、任务及幂等结果。
5. `media/storage/UploadObjectInspector.java`：复用MinIO SDK执行有超时的HEAD，区分对象缺失409与依赖故障503；网络检查不持有数据库事务。
6. `media/entity/MediaProcessingTask.java`、UploadCompletionRequestRecord及对应Mapper：V7新增持久化任务、对象快照及完成接口幂等记录。
7. `media/controller/MediaTaskController.java`：GET /api/v1/tasks/{taskId}，虽然HTTP路径是/tasks，业务及测试仍归media。
8. `media/service/MediaTaskQueryService.java`、实现及`transaction/MediaTaskQueryTransactionService.java`：复核当前身份、账号状态及任务归属，只返回`dto/MediaTaskView.java`的契约字段。
9. `media/config/`下UploadCompletion与MediaTaskQuery配置、`media/ratelimit/`对应限流器：两个独立分钟窗口，复用既有Lua算法，不修改创建重放免配额规则。

任务初始PENDING，素材PROCESSING。当前没有媒体worker，不生成进度或READY结果；真实格式、SHA-256和尺寸检查留给后续处理。
原始上传URL仍可能在到期前写入，后续worker须复核记录的对象版本/ETag/大小等快照，并固定最终可用文件，防止校验后覆盖。
`TaskReceipt`保留原PENDING受理快照；`MediaTaskView`返回任务当前状态，不暴露存储键、签名指纹或对象快照。
测试按media/config、controller、service、ratelimit组织，CompleteUploadFlowIT覆盖真实上传、并发、租约恢复及事务回滚。
`media/service/MediaTaskQueryFlowIT`独立验证任务查询：五种持久化状态、实时读取、时间不变、重试关联、
跨用户和内部任务隔离、认证后身份变化、依赖故障、独立配额及OpenAPI响应头。
`media/ratelimit/MediaTaskQueryRateLimiterTest`覆盖Redis连接故障和无法确认的脚本结果。

## 16. 本人素材校验结果怎样阅读

1. `media/controller/MediaAssetController.java`：GET /api/v1/media/assets/{assetId}，需要Bearer，无请求体，返回200及Asset契约。
2. `media/service/MediaAssetQueryService.java`与`service/impl/MediaAssetQueryServiceImpl.java`：路径校验、独立配额和数据故障映射。
3. `media/service/transaction/MediaAssetQueryTransactionService.java`：取得当前账号共享锁，复核认证版本和可读状态，按本人范围读取素材。
4. `media/mapper/MediaAssetMapper.java`的findOwned：SQL同时限定id和owner_id，不接受客户端指定ownerId。
5. `media/dto/MediaAssetView.java`：仅输出公开元数据字段，优先使用actual值；未知MIME暂用声明值，未知实际大小为0，维度未知为null。
6. `media/entity/MediaAsset.java`映射V5实际MIME/大小及V8时长/宽高；非视频时长返回null，READY缺少关键实际值时503。
7. `media/config/MediaAssetQueryConfiguration.java`、Properties及`ratelimit/MediaAssetQueryRateLimiter.java`：app.media.asset-query独立双分钟窗口。
8. `media/service/MediaAssetQueryFlowIT`、config/ratelimit测试及CompleteUploadFlowIT覆盖只读查询、权限、真实上传链路和故障分支。

查询不访问对象存储，不执行媒体校验或生成READY结果。V8仅新增可空元数据列与约束，历史迁移和已有素材状态不变。

## 17. 创建标签怎样阅读

1. `tag/controller/TagCreationController.java`：POST /api/v1/admin/tags，返回201及Tag契约和Location。
2. `tag/dto/TagWriteRequest.java`、TagView及`support/TagNames.java`：严格输入、默认值、Unicode首尾空白和响应白名单。
3. `tag/service/TagCreationService.java`与`service/impl/TagCreationServiceImpl.java`：冻结规范化输入、指纹、claim、独立配额和finish协调。
4. `tag/service/transaction/TagCreationTransactionService.java`：复核当前账号、ADMIN角色及租约，标签与幂等快照同事务提交。
5. `tag/entity/`、`tag/mapper/`对应V9的community_tag与tag_creation_request，数据库名称唯一索引裁决跨管理员竞争。
6. `tag/config/TagCreationProperties.java`绑定app.tag.creation；Configuration同时补充生成OpenAPI中的空字符串默认值。
7. `tag/ratelimit/TagCreationRateLimiter.java`：复用已有Lua算法，独立来源分钟和管理员小时窗口，成功重放免配额。
8. `auth/config/SecurityConfiguration.java`将`/api/v1/admin/**`统一交给`auth/security/AdminAuthorizationManager`；该入口按方法与路径登记允许角色，未登记路径默认拒绝，tag事务仍复核ADMIN角色。`user/mapper/UserRoleMapper.java`提供角色共享锁读取，业务流程仍全部归tag。

claim使用局部READ_COMMITTED避免缺失幂等键的间隙锁竞争，账号行锁保护同一调用者；finish保留独立写事务并再次授权。
测试目录按tag/controller、service、config和ratelimit对应生产模块；TagCreationFlowIT通过真实SQL并发屏障验证一方201、另一方409。
角色由受控初始化或后续管理流程授予，不提供公开自助ADMIN注册。标签详情、分页和修改见后续章节。

## 18. 管理员标签详情与列表怎样阅读

1. `tag/controller/TagQueryController.java`：GET /api/v1/admin/tags/{tagId}返回TagView，GET /api/v1/admin/tags返回TagPageView；仅详情在公共错误外声明RESOURCE_NOT_FOUND。
   `tag/controller/TagQueryParameters.java`集中校验后台标签查询参数：列表仅允许单个page/pageSize，详情和修改不接受查询参数；非法参数在调用业务服务和计数前返回400。
2. `auth/security/AdminAuthorizationManager.java`：为两个GET分别登记ADMIN，未登记后台路由仍默认拒绝。
3. `tag/service/TagQueryService.java`与`service/impl/TagQueryServiceImpl.java`：身份、路径及分页参数校验，共享查询配额、事务调用及依赖错误转换。
4. `tag/service/transaction/TagQueryTransactionService.java`：共享账号与角色复核；详情按主键读取，列表按创建时间和ID倒序，用同一REPEATABLE_READ快照读取总数与分页记录。
5. `tag/config/TagQueryProperties.java`、Configuration及`ratelimit/TagQueryRateLimiter.java`：列表与详情共享app.tag.query来源/管理员分钟窗口，使用独立tag-query键，不与创建配额混用。
6. `tag/dto/TagPageView.java`只返回items、page、pageSize、total，items复用TagView；复用现有MyBatis-Plus分页插件，BigInteger页码越界直接返回空页。
7. `tag/service/TagQueryFlowIT`、TagListFlowIT及config/ratelimit测试覆盖真实HTTP、MySQL、Redis；config/CommonErrorDocumentationIT按已同步的契约快照核对两个接口的完整错误集合。

查询返回当前五个Tag字段，不修改标签状态、版本或时间；WRITE_BLOCKED管理员可读，禁用账号和撤权请求被拒绝。复用已有V9表，无新增迁移。

## 19. 管理员修改或停用标签怎样阅读

1. `tag/controller/TagUpdateController.java`：PATCH /api/v1/admin/tags/{tagId}，返回200及TagView，额外声明404、版本/名称冲突和请求体错误。
2. `tag/dto/TagUpdateRequest.java`：严格JSON、字段存在标记、Unicode长度与可选字段；expectedVersion的Schema显式绑定Integer，避免JsonNode严格setter影响文档类型。
   标签修改、资料修改、上传完成请求先用BigInteger保留版本原值，再校验1～2147483647范围，超出范围返回422；验证通过后业务层仍使用Integer。取消上传的查询参数采用相同范围规则。
3. `tag/service/TagUpdateService.java`与`service/impl/TagUpdateServiceImpl.java`：身份、路径及PATCH输入校验、独立限流、事务调用和依赖错误转换。
4. `tag/service/transaction/TagUpdateTransactionService.java`：账号→角色→标签固定锁序；先授权和版本校验，再处理同值与真实修改。重名和数据库异常使整次事务回滚。
5. `tag/mapper/TagMapper.java`新增标签FOR UPDATE查询及仅更新所提供字段的条件SQL；id和expectedVersion必须匹配，版本与updated_at随真实修改一起更新。
6. `tag/config/TagUpdateProperties.java`、Configuration及`ratelimit/TagUpdateRateLimiter.java`：app.tag.update独立来源分钟、管理员小时窗口，复用已有Lua和独立tag-update键。
7. `tag/service/TagUpdateFlowIT`、config/ratelimit测试验证真实HTTP、MySQL、Redis及SQL屏障并发；config/CommonErrorDocumentationIT按已同步快照核对updateTag。

停用保留记录，允许重新启用和管理员之间管理；不更新原创建快照、不增加依赖或迁移。新审核禁止引用停用标签的消费逻辑由后续内容/审核模块实现。

## 20. 公开有效标签怎样阅读

1. `tag/controller/PublicTagController.java`：GET /api/v1/tags，返回200及TagListView，仅含items；显式关闭已认证公共错误集合。
2. `auth/security/BearerSessionFilter.PUBLIC_READ_PATHS`与SecurityConfiguration：仅按GET和精确路径公开访问并跳过Bearer认证，错误或失效凭证不影响公开读取；后台路径不受影响。
3. `tag/service/TagQueryService.java`与`service/impl/TagQueryServiceImpl.java`：复用查询入口，公开分支先独立来源限流，再拒绝未知查询参数，映射依赖故障为503。
4. `tag/service/transaction/TagQueryTransactionService.activeTags`：只读事务过滤ACTIVE，按名称和ID升序，复用TagView转换，不调用后台授权方法。
5. `tag/config/ActiveTagQueryProperties.java`、Configuration及`ratelimit/ActiveTagQueryRateLimiter.java`：app.tag.active-query来源分钟窗口，不使用用户配额，与后台计数隔离。
6. `redis/tag-active-query-rate-limit.lua`：一个来源计数键，原子检查、计数和设置60秒到期；无法确认执行结果时拒绝放行。
7. `tag/service/ActiveTagFlowIT`及config/ratelimit测试覆盖真实HTTP、MySQL、Redis、后台启停联动与并发配额；CommonErrorDocumentationIT按快照核对公开错误集合。

公开查询不分页、不缓存标签数据；停用标签不返回。账号状态不改变该公开接口的可读性，所有后台安全检查继续在原用例执行。

## 21. 创建内容与首个草稿怎样阅读

1. `content/controller/ContentCreationController.java`：接收POST /api/v1/creator/contents，拒绝未知查询参数，返回201、ContentWorkspace和Location。
2. `content/dto/CreateContentRequest.java`：严格类型、字段默认值、null和Unicode长度校验；`ContentWorkspace`、`RevisionView`只返回契约字段。`model/ContentKind`定义创建后固定的类型。
3. `content/validation/ContentDraftValidator.java`：CommonMark语法树识别真实图片，核对asset:引用集合、重复ID及ARTICLE/VIDEO约束，不执行HTML。
4. `content/service/ContentCreationService.java`与`service/impl/ContentCreationServiceImpl.java`：输入、指纹、领取幂等预留、成功重放免计配额、独立限流、最终写入和失败释放。
5. `content/service/transaction/ContentCreationTransactionService.java`：claim与finish分别复核当前账号和角色，局部READ_COMMITTED领取租约，finish核对租约令牌和引用并原子提交；私有方法分别处理重放快照和引用持久化。
6. `content/mapper/ContentCreationMapper.java`处理内容、修订和引用；`ContentCreationRequestMapper`与`entity/ContentCreationRequestRecord`处理幂等预留、续租、快照及令牌保护的完成/释放。
7. `content/config/ContentCreationProperties.java`及Configuration定义app.content.creation，并补充OpenAPI默认值；`ratelimit/ContentCreationRateLimiter.java`复用现有Lua与独立content-create作用域。
8. `V10__content_creation.sql`新增内容、修订、素材引用、标签引用和创建幂等表，外键限制跨内容指针和悬空引用，唯一约束限制单个工作稿。
9. `content/controller`、`validation`、`config`、`ratelimit`及`service`对应测试目录；ContentCreationFlowIT验证真实HTTP/MySQL/Redis、并发、回滚和租约恢复，全局错误契约测试覆盖createContent。

auth只扩展创建内容的邮箱错误码映射；内容业务全部留在content。工作区GET和草稿保存已实现，提交审核、发布和媒体READY推进仍待后续实现。

## 22. 读取本人内容工作区怎样阅读

1. `content/controller/ContentWorkspaceController.java`：GET /api/v1/creator/contents/{contentId}，拒绝未知查询参数，复用ContentWorkspace及公共错误声明。
2. `content/service/ContentWorkspaceService.java`与`service/impl/ContentWorkspaceServiceImpl.java`：身份和路径校验、独立查询配额、事务调用以及数据库故障转换。
3. `content/service/transaction/ContentWorkspaceTransactionService.java`：账号、角色共享锁复核当前权限；readOnly/REPEATABLE_READ快照读取内容、工作稿、发布稿及引用，检查指针状态及单值关联一致性。
4. `content/mapper/ContentWorkspaceMapper.java`：查询投影记录，根查询同时约束contentId与authorId，修订再次约束contentId，素材和标签按position排序；不读取创建快照。
5. `content/config/ContentQueryProperties.java`、Configuration及`ratelimit/ContentQueryRateLimiter.java`：列表和详情共用app.content.query来源/用户分钟窗口，复用现有Lua与content-query作用域。
6. `content/service/ContentWorkspaceFlowIT`覆盖真实HTTP/MySQL/Redis、所有权、删除状态、双修订、当前数据、快照并发、故障、独立限流及契约；config/ratelimit测试验证配置和原子限流。

复用V10与现有响应DTO，不新增依赖或迁移。创建接口的Location现在可直接读取；草稿保存已实现，删除恢复、审核和发布仍由后续用例实现。

## 23. 保存当前可编辑草稿怎样阅读

1. `content/controller/ContentDraftSaveController.java`：PATCH /api/v1/creator/contents/{contentId}/draft，拒绝未知查询参数，复用ContentWorkspace与公共错误响应。
2. `content/dto/EditDraftRequest.java`：BigInteger保留版本输入供范围校验；Setter标记字段存在，区分省略、null和空值。只允许已定义的修改字段。
3. `content/service/ContentDraftSaveService.java`与`service/impl/ContentDraftSaveServiceImpl.java`：输入校验、预检、独立计数、重新校验并保存，转换数据库故障；不使用幂等键缓存或重放。
4. `content/service/transaction/ContentDraftSaveTransactionService.java`：preflight与save各自持有短事务，复用inspect完成账号、角色、版本、状态、合并和引用检查；Redis位于两个事务之间。真实保存才递增版本并按变化替换引用，同值保持时间。
5. `content/validation/ContentDraftValidator.java`复用Markdown语法树和去重规则；`ContentReferenceValidator.java`供创建与保存共用素材归属/用途及标签存在性检查。
6. `content/mapper/ContentDraftSaveMapper.java`：所属内容与当前修订行锁、版本条件更新、仅DRAFT文本更新以及引用替换，禁止修改已发布修订或重新分配工作指针。
7. `content/config/ContentDraftSaveProperties.java`及Configuration定义app.content.draft-save；`ratelimit/ContentDraftSaveRateLimiter.java`复用分钟窗口Lua，使用独立content-draft-save作用域。
8. `content/controller/ContentDraftSaveEndpointTest`及`service/ContentDraftSaveFlowIT`验证类型/存在标记、同值、版本、权限、并发、冻结快照、回滚、引用和契约；config/ratelimit对应测试验证配置与原子额度。

复用V10与工作区读取组件，不新增迁移和依赖；读取返回值加入现有写事务，不额外占用工作区查询额度。提交审核、新建后续修订、删除恢复和发布仍未实现。

## 24. 本人创作及回收站分页怎样阅读

1. `content/controller/ContentWorkspaceController.java`新增GET /api/v1/creator/contents，仅允许单个page/pageSize/lifecycle/publicationStatus/kind；与详情复用查询参数检查。
2. `content/service/ContentWorkspaceService.java`与`service/impl/ContentWorkspaceServiceImpl.java`新增listMyContents，校验分页和枚举，默认ACTIVE，复用详情查询额度与故障转换。
3. `content/service/transaction/ContentWorkspaceTransactionService.java`新增list：同一只读REPEATABLE_READ快照复核资格、COUNT、分页及批量加载修订/引用，与详情共用投影和一致性检查；空指针保留null。
4. `content/mapper/ContentWorkspaceMapper.java`：COUNT和页面SQL复用本人及筛选条件，固定updated_at/id倒序；批量读取页内工作稿和发布稿，限制修订所属contentId，引用按position排序，避免N+1查询。
5. `content/dto/WorkspacePage.java`返回items/page/pageSize/total，items复用ContentWorkspace；BigInteger页码超末页直接返回空页，不加契约外上限。
6. `content/controller/ContentListEndpointTest`覆盖参数边界、重复/未知字段与计数前拒绝；`content/service/ContentListFlowIT`覆盖真实HTTP/MySQL/Redis、回收站和组合筛选、稳定排序、批量引用、权限、并发快照、故障与共享额度。

复用app.content.query、V10与响应工作区模型；不新增配置组、依赖或迁移。列表只读取回收站状态，删除恢复仍待后续用例实现。

## 25. 本人修订媒体预览怎样阅读

1. `content/controller/ContentMediaPreviewController.java`：GET /api/v1/creator/contents/{contentId}/revisions/{revisionId}/media/{assetId}/access，拒绝未知查询参数，返回MediaAccess，不要求幂等键。
2. `content/service/ContentMediaPreviewService.java`与`service/impl/ContentMediaPreviewServiceImpl.java`：路径、独立额度、prepare、事务外对象核验、finish及数据库故障转换。
3. `content/service/transaction/ContentMediaPreviewTransactionService.java`：两次复核当前资格、内容/修订/素材归属、真实引用及用途，比较读取绑定，最终本地签名并原子写最小审计；不把历史修订或回收站内容视为公开对象。
4. `content/mapper/ContentMediaPreviewMapper.java`：共享锁读取本人内容、所属修订及引用用途，插入只含主体/目标/requestId/时间的审计记录。与`media/mapper/MediaAssetMapper.findOwnedForShare`组合校验素材。
5. `media/storage/MediaObjectReader.java`：验证独立ready key、存储指纹和实际元数据，在事务外HEAD核对ETag/大小；`MediaReadSigner.java`离线签发GET及no-store/MIME响应覆盖参数。
6. `media/dto/MediaAccess.java`只有契约四个字段，toString隐藏URL；`StorageProperties.identity`保持旧上传指纹算法，UploadSigner继续复用同一结果。
7. `content/config/ContentMediaPreviewProperties.java`及Configuration定义app.content.media-preview，默认TTL300秒、来源120/分钟、用户60/分钟；RateLimiter使用独立content-media-preview作用域。
8. `V11__media_preview_binding_and_audit.sql`增加素材成组的结果key/ETag/存储指纹及content_media_preview_audit；不回填READY、不改变桶版本控制，不改写已执行迁移。
9. `ContentMediaPreviewFlowIT`覆盖真实HTTP/MySQL/Redis/MinIO、双重归属和引用、历史/回收站、权限重检、旧PUT隔离、读取绑定和审计失败；Endpoint/config/ratelimit及`media/storage/MediaReadSignerTest/IT`覆盖路径、额度、整秒过期、Range及篡改拒绝。

普通查询不调用此签名流程；处理worker仍未实现。后续worker须把校验结果写入全新ready key，设置实际元数据及绑定，再原子推进READY。已发签名仅由到期窗口约束，后续申请会重新校验权限和引用。

## 26. 资源与测试

- `src/main/resources/application.yml`：环境连接与业务参数。
- `src/main/resources/db/migration/`：Flyway迁移，已执行版本保持不变。
- `src/main/resources/redis/`：Redis限流Lua脚本。
- `src/test/java/`：按生产包结构组织单元测试、Web测试及事务集成测试。

移动包或重命名后先清理旧编译产物，防止旧包的class残留被Spring扫描。
`mvn test` 只运行不依赖中间件的单元/Web测试；`*IT` 由 Failsafe 在 `verify` 阶段执行。
加载本地环境变量并启动中间件后，可以运行：

```powershell
$env:EMAIL_CHALLENGE_DELIVERY_ENABLED = "false"
$env:MEDIA_UPLOAD_CLEANUP_ENABLED = "false"
mvn verify
```

测试关闭自动邮件投递和自动文件清理；清理集成测试按自身任务ID显式执行，只操作各自生成的记录和对象。
运行共享本地数据库的完整集成测试前，停止独立开发后端，防止其后台执行器与测试争抢人为调到期的任务；CI应使用独立数据库环境。
