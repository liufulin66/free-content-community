# 业务结构约定

- 后端先按业务模块分包，再在模块内按职责分层。新增或修改用例前，先确定它属于哪个业务模块。
- 同一用例的 Controller、DTO、Service接口、ServiceImpl、事务组件、限流器、配置及测试应放在所属模块中。
- `user`负责当前账号与用户资料业务，包括修改当前账号密码。改密用例统一放在`user`，配置分组为`app.user.password-change`。
- `auth`负责验证码申请、注册、登录、退出、验证码找回及会话认证，并提供现有密码哈希、认证上下文和会话存储能力。
- 复用`auth.security`、`auth.session`等安全能力，不代表把其他模块的整个业务流程迁入`auth`。Controller调用所属业务模块的Service接口；不要跨模块调用Controller。
- 主业务Service采用接口与`service.impl`结构；事务组件、限流器和工具类可保留具体类。测试目录与生产代码的业务模块对应。
- 新业务配置按所属模块归类；移动配置时同步Properties、application.yml、测试及文档，并保留已约定的接口和环境变量兼容性。
- 移动或重命名Java类后执行干净构建，避免旧class残留导致重复Bean或路由。实际目录及阅读顺序见`backend/STRUCTURE.md`。

- `content.workspace` 归集创作、草稿、本人工作区和本人预览；`content.review` 归集审核提交、查询、预览、决定及撤销。共享读取保持在 `content.service.read`，共享持久化参数与投影放在 `content.model.persistence`。
- 内容、媒体、标签通过 `user.service.AccountAccessService` 在已有事务内读取账号和角色锁；业务授权规则仍由所属模块判断，不缩短原事务的锁持续时间。
- 共用 Redis 限流执行放在 `common.ratelimit`；业务键、配额和幂等重放是否计数继续由业务用例决定。配置按模块统一注册，UTC 时钟和调度启用放在全局 `config`。
- `content.review` 和内容共享能力不得依赖 `content.workspace` 内部实现；共享查询/行锁使用 `content.mapper`，共享草稿数据使用 `content.model.ContentDraftData`。本人查询限流器及参数放在 `content.ratelimit` / `content.config`，保持工作区、列表和审核反馈共享 `app.content.query` 配额。

- `content.browse` 负责已发布内容读取；禁止依赖 workspace/review 的用例实现。只读取公开发布指针及其修订，公开查询配额与本人查询隔离。公开用户资料复用 user 的只读投影，不返回账号实体。

- 公开列表使用(firstPublishedAt,id)键集分页，游标绑定接口、用户、筛选、limit及固定快照/期限；列表投影必须批量加载引用、作者与统计，不得循环调用详情用例。V16公开排序索引已执行，不得改写。
