# 开发与验证

## 2026-11-11 公开媒体短时读取验证

新增 `GET /api/v1/contents/{contentId}/media/{assetId}/access`，规则与调用链见[公开媒体取址](content-media-access.md)。
按红→绿→重构验证当前发布修订关联、归属及用途、目标非READY的409、其他素材异常的404、READY结果绑定、
事务外MinIO HEAD、最终共享锁重检、严格修订绑定、可选认证、独立签名配额、配置与OpenAPI。
真实下载图片字节并验证视频结果对象的Range GET；确认公开读取不写敏感预览审计、业务数据或Outbox。
最终在源码哈希一致的 `.local-dev/structure-verification/published-media-final/backend` 执行
`mvn -B -ntp clean verify`：2097项全部通过，其中945项单元/Web、1152项真实集成，零失败、零错误、零跳过，打包成功。
543个源码/资源/pom文件与工作区一致；原有36个操作错误契约保留，新增后覆盖37个操作。
无新增依赖或迁移，V1～V17、infra/.env及IDE设置保持原样。未提交、推送或启动独立开发后端。
日志 `.local-dev/structure-published-media-final.log`，统计 `.local-dev/published-media-task/verification-summary.json`。

## 2026-11-11 指定作者公开作品验证

新增 `GET /api/v1/users/{userId}/contents`，行为、调用链与查询数量见[作者公开作品](content-author-list.md)。
按红→绿→重构完成作者隔离、稳定键集分页、游标用途绑定、参数及认证边界、共享配额和依赖错误处理。
新增并执行 V17 作者公开排序索引，EXPLAIN 验证可按作者及公开状态定位后倒序扫描；V1～V16 保持原内容。
最终在源码哈希一致的 `.local-dev/structure-verification/author-list-final/backend` 执行
`mvn -B -ntp clean verify`：1999 项全部通过，其中 909 项单元/Web、1090 项真实集成，零失败、零错误、零跳过，打包成功。
531 个源码/资源/pom 文件与工作区一致；原有 35 个操作错误契约保留，新增后覆盖 36 个操作。
日志 `.local-dev/structure-author-list-final.log`，统计 `.local-dev/author-content-task/verification-summary.json`。

## 2026-11-11 公开内容列表验证

新增 `GET /api/v1/contents`，分页、游标、批量读取与配置见 [公开内容列表](content-list.md)。
最终在源码一致的隔离副本执行 `mvn -B -ntp clean verify`，1854项全部通过：
841项单元/Web、1013项真实集成，零失败、零错误、零跳过，打包成功。
新增并执行V16公开排序索引，既有迁移不改写；列表与详情共享公开查询额度。
日志 `.local-dev/structure-list-final-v2.log`，统计 `.local-dev/public-list-verification-summary.json`。

## 2026-11-11 已发布内容详情验证

新增 `GET /api/v1/contents/{contentId}`，行为与分包见 [已发布内容浏览](content-browse.md)。
在源码哈希一致的隔离副本运行 `mvn -B -ntp clean verify`，1805 项全部通过：
821 项单元/Web、984 项真实集成，零失败、零错误、零跳过，打包成功。
日志 `.local-dev/structure-public-final.log`，统计 `.local-dev/public-content-verification-summary.json`。

## 2026-11-11 内容用例依赖收口验证

本轮消除审核与共享层对 `content.workspace` 的 8 处直接引用：读取 Mapper 和行锁 Mapper 归入
`content.mapper`，后台详情使用共享 Reader，提交审核直接装配已加锁内容；公共校验改用
`ContentDraftData`，共享查询限流器及参数归入 `content.ratelimit` / `content.config`。
本人审核局部投影改名 `OwnedReviewRow`，17 个参数绑定测试改名为 `*PropertiesTest`。

`ContentModuleBoundaryTest` 先真实失败并列出上述引用，迁移后变绿；全部单元/Web 测试通过后完成命名整理。
最终在源码哈希一致的 `.local-dev/structure-verification/boundary-final/backend` 执行
`mvn -B -ntp clean verify`：1742 项检查通过，其中 790 项单元/Web、952 项真实集成，零失败、零错误、零跳过，打包成功。
日志为 `.local-dev/structure-boundary-final.log`，统计为 `.local-dev/content-boundary-verification-summary.json`。

全部 124 条 Mapper SQL 注解内容保持一致；原有锁顺序、事务和幂等边界保持原规则。
工作区、列表和本人审核反馈继续共享 `app.content.query` 及 `{content-query}` 配额。
V1～V15、原始 OpenAPI、固定错误契约、应用及环境示例配置、IDE 设置均未改动；本轮未提交或推送。

## 2026-11-11 结构整理验证

完成全局时钟与调度归位、模块参数集中注册、内容共享数据模型与数据库参数拆分、公共 Redis 限流执行器、
账号事务访问入口、`content.workspace` / `content.review` 分组、测试清理复用及文档拆分。

按红→绿→重构推进：先验证独立时钟、异常限流结果和账号事务边界，再在原有业务测试保护下迁移。
最终执行 `mvn -B -ntp clean verify`，通过 1741 项检查：789 项单元/Web、952 项真实集成，
零失败、零错误、零跳过，打包成功。集成测试使用真实 MySQL、Redis、MinIO，串行执行并关闭邮件投递和上传清理。

IDE 曾占用共享 `backend/target` 并生成 ECJ 错误 class，因此最终验证在
`.local-dev/structure-verification/final/backend` 的源码副本中执行；复制前后以及验证结束后均核对源码、资源、
测试与 pom 的 SHA256，确认与当前工作区一致。日志为 `.local-dev/structure-final.log`。

V1～V15、`application.yml`、`infra/.env.example`、固定错误契约、原始 OpenAPI 和
`.vscode/settings.json` 均保持原内容，所有原有 Mapper SQL 注解内容也保持不变。
本次没有提交、推送或启动独立开发后端；不据测试进程声明开发端口在线。

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### Spring Boot 4 注意事项（本次踩过的坑）

1. **Web starter**：`spring-boot-starter-web` 已标记 deprecated，使用 `spring-boot-starter-webmvc`。
2. **Flyway**：Boot 4 把自动配置拆到独立模块，必须用 `spring-boot-starter-flyway`；只加 `flyway-core` 不会执行迁移。
3. **Jackson 3**：`spring.jackson.serialization.write-dates-as-timestamps` 在 Jackson 3 已不存在，配置它会导致启动失败；Jackson 3 默认输出 ISO-8601 字符串。
4. **HTTP 422**：使用 `HttpStatus.UNPROCESSABLE_CONTENT`；`UNPROCESSABLE_ENTITY` 在 Spring 7 已过时。
5. **Git Bash 环境变量**：不要把 `/` 作为环境变量值，MSYS 会把它转成 Git 安装目录下的路径。RabbitMQ vhost 使用 Spring 默认的 `/`，不通过 `.env` 传入。

JSON请求严格匹配契约类型：数字和布尔值不会自动转换成字符串，枚举只接受名称，不接受数字序号或序号字符串。
类型不匹配返回400 `MALFORMED_REQUEST`；缺失、空值和字符串内容继续按各接口的字段规则校验。
Swagger显式声明验证码申请202、注册201和登录201，与实际成功响应一致。
