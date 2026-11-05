# 后端结构与阅读指南

后端是按业务模块分包的Spring Boot应用。先按业务寻找`auth`或`user`，
再按控制层、业务入口、数据访问等职责定位代码。

## 1. 顶层模块

路径：`src/main/java/com/freecontent/community/`。

| 包 | 职责 |
| --- | --- |
| `auth` | 验证码、注册，以及后续登录认证 |
| `user` | 用户账号、公开资料相关模型、角色及数据库访问 |
| `common` | 统一响应、异常、请求标识、通用校验 |
| `config` | Web、MyBatis、OpenAPI等全局配置 |

`FreeContentApplication` 是启动入口。所有业务包都在其扫描范围内。

## 2. 认证模块按职责归类

```text
auth/
├── controller/
│   ├── EmailChallengeController.java
│   └── RegistrationController.java
├── service/
│   ├── EmailChallengeService.java
│   ├── RegistrationService.java
│   └── transaction/
│       ├── EmailChallengeTransactionService.java
│       ├── RegistrationTransactionService.java
│       └── RegistrationVerificationService.java
├── ratelimit/
│   ├── EmailChallengeRateLimiter.java
│   └── RegistrationRateLimiter.java
├── job/
│   └── EmailDeliveryWorker.java
├── security/
│   ├── EmailChallengeCrypto.java
│   └── PasswordHasher.java
├── config/
├── dto/
├── entity/
├── mapper/
└── model/
```

| 包 | 内容和边界 |
| --- | --- |
| `controller` | 解析HTTP请求、触发字段校验、调用主业务入口、包装响应 |
| `service` | 组织完整业务流程；阅读功能时从这两个主入口开始 |
| `service.transaction` | 业务层内部的事务处理，明确哪些数据库操作一起提交或回滚 |
| `ratelimit` | Redis配额检查及Retry-After计算 |
| `job` | 定时扫描邮件任务、认领、发送、重试和结果回写 |
| `security` | 密码哈希、验证码HMAC摘要及AES-GCM加解密 |
| `config` | `Properties`读取并校验参数；`Configuration`注册Spring Bean |
| `dto` | HTTP请求与响应数据结构 |
| `entity` | 数据库记录映射 |
| `mapper` | 数据库查询、条件更新和插入 |
| `model` | 验证码用途等业务类型 |

`service.transaction` 属于业务层内部。HTTP控制层调用主Service，
主Service协调限流、密码计算及事务组件。Mapper承接数据库操作。
认证事务会调用`user.mapper`，以便账号、角色与验证码消费共享同一个本地事务。

## 3. 发送验证码怎样阅读

按顺序打开：

1. [EmailChallengeController](src/main/java/com/freecontent/community/auth/controller/EmailChallengeController.java)：
   HTTP路径为`POST /api/v1/auth/email-challenges`。
2. [EmailChallengeService](src/main/java/com/freecontent/community/auth/service/EmailChallengeService.java)：
   规范化邮箱、检查限流、生成验证码、计算摘要及密文。
3. [EmailChallengeTransactionService](src/main/java/com/freecontent/community/auth/service/transaction/EmailChallengeTransactionService.java)：
   锁定旧申请、处理重发、同事务保存新申请与邮件任务。
4. [EmailDeliveryWorker](src/main/java/com/freecontent/community/auth/job/EmailDeliveryWorker.java)：
   后台认领并投递已保存任务，按租约令牌回写结果。

前三步完成后返回202；后台投递独立执行。SMTP操作不占用长时间数据库事务。

## 4. 邮箱注册怎样阅读

按顺序打开：

1. [RegistrationController](src/main/java/com/freecontent/community/auth/controller/RegistrationController.java)：
   HTTP路径为`POST /api/v1/auth/registrations`。
2. [RegistrationService](src/main/java/com/freecontent/community/auth/service/RegistrationService.java)：
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

## 5. 资源与测试

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
