# 业务结构约定

- 后端先按业务模块分包，再在模块内按职责分层。新增或修改用例前，先确定它属于哪个业务模块。
- 同一用例的 Controller、DTO、Service接口、ServiceImpl、事务组件、限流器、配置及测试应放在所属模块中。
- `user`负责当前账号与用户资料业务，包括修改当前账号密码。改密用例统一放在`user`，配置分组为`app.user.password-change`。
- `auth`负责验证码申请、注册、登录、退出、验证码找回及会话认证，并提供现有密码哈希、认证上下文和会话存储能力。
- 复用`auth.security`、`auth.session`等安全能力，不代表把其他模块的整个业务流程迁入`auth`。Controller调用所属业务模块的Service接口；不要跨模块调用Controller。
- 主业务Service采用接口与`service.impl`结构；事务组件、限流器和工具类可保留具体类。测试目录与生产代码的业务模块对应。
- 新业务配置按所属模块归类；移动配置时同步Properties、application.yml、测试及文档，并保留已约定的接口和环境变量兼容性。
- 移动或重命名Java类后执行干净构建，避免旧class残留导致重复Bean或路由。实际目录及阅读顺序见`backend/STRUCTURE.md`。
