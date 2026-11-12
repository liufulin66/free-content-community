# 后端结构与阅读指南

后端是按业务模块分包的 Spring Boot 单体应用。同一业务的 Controller、DTO、Service 接口、
`service.impl`、事务、限流、配置和测试放在所属模块；分包约定见 [AGENTS.md](../AGENTS.md)。

## 目录与入口

```text
src/main/java/com/freecontent/community/
├── FreeContentApplication.java
├── auth/                         # 验证码、注册、登录、退出、找回密码、认证与会话
├── user/                         # 当前账号、公开个人主页、资料、改密、角色及账号访问能力
│   ├── model/AccountSnapshot.java
│   ├── service/AccountAccessService.java
│   └── service/impl/AccountAccessServiceImpl.java
├── media/                        # 上传、完成、取消、任务与素材、存储读写签名
├── tag/                          # 标签管理及公开有效标签
├── content/
│   ├── browse/                   # 公开详情/全站及作者列表；可选认证、游标、批量投影及公开配额
│   ├── workspace/                # 创建、草稿保存、本人工作区/列表、本人预览
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── mapper/
│   │   ├── ratelimit/
│   │   ├── config/
│   │   └── service/
│   │       ├── impl/
│   │       └── transaction/
│   ├── review/                   # 提交、本人反馈、后台查询/预览、审批与撤销
│   │   └── controller/dto/entity/mapper/ratelimit/config/service/...
│   ├── config/ContentModuleConfiguration.java
│   │           ContentQueryProperties.java
│   ├── dto/                      # 工作区及修订的共享 HTTP 投影
│   ├── mapper/                   # ContentReadMapper / ContentLockMapper
│   ├── model/ContentDraftData.java
│   ├── model/persistence/        # 共享数据投影与数据库写入参数
│   ├── ratelimit/ContentQueryRateLimiter.java
│   ├── service/read/ContentWorkspaceReader.java
│   └── validation/               # 草稿结构及媒体/标签引用的共享校验
├── common/
│   ├── api/                      # 响应、请求标识、公共响应头及文档注解
│   ├── error/                    # RFC 9457 错误响应
│   ├── validation/               # 通用字段校验
│   └── ratelimit/RedisRateLimitExecutor.java
└── config/                       # Web、JSON、MyBatis、OpenAPI、时钟、调度
```

`content.workspace`、`content.review` 和 `content.browse` 是同一内容模块内部的职责分组，共用原有数据库和事务管理器。
内容版本、修订指针和活动审核指针仍属于同一聚合，不因分包拆开事务。主业务 Service 继续采用接口与 `service.impl`。

| 业务 | 行为约定与真实调用链 |
| --- | --- |
| 认证及会话 | [auth](docs/auth.md) |
| 账号与资料 | [user](docs/user.md) |
| 上传与媒体 | [media](docs/media.md) |
| 标签 | [tag](docs/tag.md) |
| 内容创作 | [workspace](docs/content-workspace.md) |
| 内容审核 | [review](docs/content-review.md) |
| 已发布内容浏览 | [详情](docs/content-browse.md) · [列表](docs/content-list.md) · [作者作品](docs/content-author-list.md) · [媒体取址](docs/content-media-access.md) |
| 错误契约与 OpenAPI | [api-errors](docs/api-errors.md) |
| 开发与兼容注意事项 | [development](docs/development.md) |

## 业务调用与事务边界

```text
Controller → Service 接口 → service.impl → service.transaction → Mapper
```

Controller 解析 HTTP、触发字段校验、调用业务接口并包装响应。Service 实现编排限流、幂等、
高成本计算及事务组件；事务组件明确哪些数据一起提交。不要跨模块调用 Controller。

事务组件保持独立 Spring Bean，主 Service 通过代理调用；合并到同一个对象内的普通方法调用
可能绕过事务代理。验证码错码计数、幂等租约、最终业务写入各自原有提交边界继续保留。

- 密码哈希和 SMTP 在数据库写事务外执行，降低锁持有时间。
- 媒体读取按“授权短事务 → 事务外 MinIO HEAD → 最终授权重检、本地签名和审计提交”执行。
- 审批和撤销按原有顺序加锁，状态、审计、Outbox 与成功快照继续同事务提交。
- Outbox 当前只保存待发送事件，本次结构整理没有增加 MQ 投递器或消费者。

`ContentWorkspaceReader` 只在已经完成授权的事务内读取、批量装配工作区与冻结修订，
本人和后台入口分别授权。批量读取修订与引用的方式保留，避免列表出现 N+1 查询。

共享 Reader 依赖中立的 `content.mapper.ContentReadMapper`。后台详情通过 `reader.findContent` 获取关联内容，
审核提交使用 `reader.workspace(content)` 装配已加锁的内容，不调用创作组的工作区事务组件。
`ContentLockMapper.lockOwned/lockRevision` 为草稿保存和提交审核提供原有行锁 SQL，锁顺序与事务边界保持不变。

## 模块协作与共享模型

`user.service.AccountAccessService` 为内容、媒体、标签提供账号共享锁、排他锁及角色共享锁读取。
它使用 `MANDATORY` 传播：调用方没有事务时立即拒绝，存在事务时加入同一个事务，不另开短事务。
返回的 `AccountSnapshot` 只含身份、认证版本、邮箱验证和状态标记，不暴露密码或资料实体。

业务模块继续核对各自的账号状态、角色、邮箱验证和 `authVersion`。查询允许的账号状态与写入
允许的状态不必相同；认证过滤器通过后也必须在业务事务中重新复核。锁类型、锁顺序及错误码保持原约定。

认证事务中创建账号、修改密码与验证码消费仍共用本地事务，因此 `auth` 对 `user.mapper` 的既有写入协作保留。
`user` 继续复用 `auth.security` 的密码能力、认证上下文及 `auth.session` 的会话能力。

内容模块的共享 `ContentRow`、`RevisionRow`、`ReviewRow` 和引用投影位于 `content.model.persistence`，
不再依赖某个查询 Mapper 的内部类型。`ContentInsert`、`RevisionInsert`、`DraftUpdate`、
`ReviewInsert`、`ReviewEventReference` 是数据库参数，HTTP DTO 继续负责接口表示与校验。
只供一个 Mapper 使用的局部投影可以继续留在该 Mapper 中。

本人审核查询的局部投影命名为 `OwnedReviewRow`，额外包含内容当前版本，避免与共享的 `ReviewRow` 混淆。
`ContentDraftData` 是创作与审核共用的内部草稿数据；创作请求在 DTO 边界转换，审核从修订数据构造。
`ContentDraftValidator`、`ContentReferenceValidator` 仅接收内部模型，不依赖创作请求 DTO。

审核用例、共享读取、校验、Mapper、限流及模型均不依赖 `content.workspace` 内部实现。
模块配置仍可显式引用各组 Properties 完成 Bean 注册。`ContentModuleBoundaryTest` 检查上述依赖边界，防止再次倒向创作组。

## 配置与限流

`AuthModuleConfiguration`、`UserModuleConfiguration`、`MediaModuleConfiguration`、
`TagModuleConfiguration`、`ContentModuleConfiguration` 集中注册各模块参数；原有配置分组、
环境变量及 Properties 校验继续保留。具有实际 Bean 定义的注册密码哈希、内容/标签 OpenAPI 配置仍独立。

`config.TimeConfiguration` 提供 UTC `Clock`，保留 `authenticationClock` 作为 Bean 别名；
`config.SchedulingConfiguration` 统一启用调度，各模块继续使用自身开关控制邮件投递和上传回收。

`RedisRateLimitExecutor` 负责 Lua 执行、结果结构校验、Retry-After 换算及依赖异常转换。
业务限流器留在所属模块，继续定义 Redis 键、配额、提示和调用时机；认证等专用限流规则保留各自实现。

本人查询限流器位于 `content.ratelimit`，参数位于 `content.config`：本人工作区、创作列表和审核反馈
共享 `app.content.query` 及 `{content-query}` 键，继续合并计算配额，不因用例分组而新增独立窗口。
配置绑定测试采用 `*PropertiesTest` 命名；实际 Bean 配置及 SMTP 配置测试保留 `*ConfigurationTest`。

通用双窗口脚本位于 `src/main/resources/redis/rate-limit/`，窗口和 Redis 键保持原规则。
幂等重放是否计数由业务用例决定：创建上传/标签/内容、提交审核和审批的成功重放免配额，
上传完成重放和重复撤销继续计数；不把这些规则放入公共执行器。

## 资源与测试

- `src/main/resources/application.yml`：应用配置与各模块参数。
- `src/main/resources/db/migration/`：Flyway V1～V17，已执行迁移不得改写或删除。
- `src/main/resources/redis/`：会话、专用限流及共用限流脚本。
- `src/test/java/`：与生产业务模块及 workspace/review 分组对应。
- `src/test/java/com/freecontent/community/content/support/ContentTestSupport.java`：内容测试关联构造与重复清理。
- `src/test/resources/contracts/api-v1-error-responses.json`：固定错误契约快照。

`mvn test` 运行单元/Web 测试，`mvn clean verify` 还运行真实 MySQL、Redis、MinIO 集成测试。
集成测试必须串行；先在当前进程静默加载 `infra/.env`，并设置：

```powershell
$env:EMAIL_CHALLENGE_DELIVERY_ENABLED = "false"
$env:MEDIA_UPLOAD_CLEANUP_ENABLED = "false"
mvn clean verify
```

移动或重命名 Java 类后执行干净构建。IDE 若向共享 `target` 写入 ECJ 错误 class，
应先区分源码错误与构建产物；必要时使用源码哈希一致的临时副本隔离构建，不覆盖编辑器缓冲区。
测试临时数据按各测试创建的账号或 Redis 前缀清理，不清空共享数据库和中间件卷。

## 已发布内容读取边界

公开详情独立归入 `content.browse`，Service 保留接口与 `service.impl`，事务、Mapper、DTO、配置、限流与测试同组。
它只查询发布修订，不借用作者工作区或后台审核用例；依赖边界测试同时阻止 browse 引入 workspace/review 实现。
作者基础公开资料由 `user.service.read.PublicUserReader` 在同一事务读取，不返回完整账号实体。
公开查询使用独立配额，工作区、创作列表和本人审核反馈继续共享原有本人配额。

公开个人主页归 `user`，通过 `PublicUserService` / `service.impl` / `PublicUserTransactionService` 读取；
用户资料和公开内容数在同一个 readOnly + REPEATABLE_READ 事务中完成。内容模块通过
`content.service.read.PublicContentCountReader` 提供 MANDATORY 数量读取，复用公开内容的 VISIBLE 条件，
用户模块不复制内容 SQL 或调用内容详情用例。主页使用独立 `app.user.public-query` / `{user-public-query}` 配额。

## 公开列表的连续浏览与批量读取

公开列表采用独立PublicContentListService接口与service.impl，复用browse的Controller、Mapper、
PublicContentAssembler和公开查询限流；禁止逐项调用详情用例。原单条详情的错误边界保持不变。
游标密钥归browse配置，payload绑定接口、用户范围、筛选、limit、快照上界和扫描位置；不复用auth密钥。
纯投影数据异常可以按条排除，所有数据库和批量读取故障必须返回依赖错误。
V16是本轮新增的公开排序索引，已执行迁移不得改写。

作者作品列表同属 content.browse，采用 UserContentListService 接口与 service.impl，
复用 PublicContentListTransactionService 的批量装配，在同一只读快照中确认作者存在并读取作品。
作者游标将接口和目标作者加入 AES-GCM 用途及载荷绑定，禁止与全站列表互换；旧全站游标保持兼容。
正常非空页仍为固定 5 次业务 SQL，作者资料同时用于存在性确认与投影。与公开详情、全站列表共享内容公开配额。
V17 新增作者公开键集索引，已执行；V1～V17 不得改写。详细行为见 [作者作品](docs/content-author-list.md)。

## 公开媒体读取授权

公开媒体取址同属 content.browse，采用 PublishedMediaAccessService 接口与 service.impl。
PublishedMediaAccessTransactionService 以“授权短事务 → 事务外 HEAD → 最终当前读短事务”完成授权；
PublishedMediaAccessMapper 按内容、修订、引用、批量素材和标签顺序取得共享锁，最终签名完成后释放。
复用媒体模块的 MediaObjectReader、MediaReadSigner 和 MediaAccess，不依赖 workspace/review 用例实现。
签名配额归 app.content.public-media-access，与内容浏览额度独立；不写业务审计、浏览量或 Outbox。
无新增依赖或迁移。详细调用链、409/404 优先级及绑定变化策略见[公开媒体取址](docs/content-media-access.md)。
