# 后端结构与阅读指南

后端是按业务模块分包的Spring Boot应用。先按业务寻找`auth`或`user`，
再按控制层、业务入口、数据访问等职责定位代码。

## 1. 顶层模块

路径：`src/main/java/com/freecontent/community/`。

| 包 | 职责 |
| --- | --- |
| `auth` | 验证码、注册、密码登录及Redis会话认证 |
| `user` | 用户账号、公开资料相关模型、角色及数据库访问 |
| `common` | 统一响应、异常、请求标识、通用校验 |
| `config` | Web、MyBatis、OpenAPI等全局配置 |

`FreeContentApplication` 是启动入口。所有业务包都在其扫描范围内。

## 2. 认证模块按职责归类

```text
auth/
├── controller/
│   └── AuthController.java
├── service/
│   ├── EmailChallengeService.java       # 对外业务接口
│   ├── RegistrationService.java         # 对外业务接口
│   ├── LoginService.java                # 对外业务接口
│   ├── SessionAuthenticationService.java
│   ├── impl/
│   │   ├── EmailChallengeServiceImpl.java
│   │   ├── RegistrationServiceImpl.java
│   │   └── LoginServiceImpl.java
│   └── transaction/
│       ├── EmailChallengeTransactionService.java
│       ├── RegistrationTransactionService.java
│       └── RegistrationVerificationService.java
├── ratelimit/
│   ├── EmailChallengeRateLimiter.java
│   ├── RegistrationRateLimiter.java
│   └── LoginRateLimiter.java
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

| 包 | 内容和边界 |
| --- | --- |
| `controller` | 解析HTTP请求、触发字段校验、调用主业务入口、包装响应 |
| `service` | 对外业务接口及内部会话认证组件；Controller依赖三个业务接口 |
| `service.impl` | 实现验证码申请、注册和登录业务接口，组织完整业务流程 |
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

认证模块的HTTP入口统一放在AuthController；验证码、注册、登录的业务Service接口继续独立。
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
   校验邮箱、用途、状态及有效期；维护错码次数和锁定状态。
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
auth_version独立于资料version；未来改密、找回及禁用账号的数据库事务必须递增它。
版本失配拒绝旧会话，不依赖Redis物理删除及时完成。请求已通过认证后的并发业务写入，
仍需在相应业务事务中检查当前状态。

本次只实现登录入口和认证基础；GET /api/v1/me、退出、改密和P1会话列表仍待实现。
添加新接口时须依据契约声明公开、可选认证及角色权限，不能统一放行。

## 6. 资源与测试

- `src/main/resources/application.yml`：环境连接与业务参数。
- `src/main/resources/db/migration/`：Flyway迁移，已执行版本保持不变。
- `src/main/resources/redis/`：Redis限流Lua脚本。
- `src/test/java/`：按生产包结构组织单元测试、Web测试及事务集成测试。

移动包或重命名后先清理旧编译产物，防止旧包的class残留被Spring扫描。
加载本地环境变量后，可以运行：

```powershell
$env:EMAIL_CHALLENGE_DELIVERY_ENABLED = "false"
mvn clean "-Dtest=*Test,*Tests,*IT" test
```

测试关闭邮件后台投递；集成测试只操作各自生成的测试记录。
