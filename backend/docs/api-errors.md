# 接口错误响应

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### Controller公共错误响应文档

运行时仍由`GlobalExceptionHandler`统一生成`application/problem+json`；Controller中的错误声明用于OpenAPI文档。
公共错误模型复用`ProblemDetailResponse`，错误码、HTTP状态和标题复用`ErrorCode`，不在每个Controller重复拼接。

`common/api/ApiErrorResponses.java`是简短文档注解，公共生成逻辑集中在`config/ProblemResponseCustomizer.java`；`OpenApiConfig.java`负责API基本信息、安全方案及公共模型注册。
默认包含已认证业务接口的400、401、403、422、429、500、503及对应通用业务错误码；
其中403公共集合为PERMISSION_DENIED和ACCOUNT_RESTRICTED。EMAIL_VERIFICATION_REQUIRED按接口契约显式声明，当前已实现接口中仅密码登录、创建上传使用这一专用错误码。
Controller只补充本接口特有错误，例如本人查询：

```java
@ApiErrorResponses(RESOURCE_NOT_FOUND)
```

创建标签则补充TAG_NAME_CONFLICT、IDEMPOTENCY_KEY_REUSED、REQUEST_IN_PROGRESS、PAYLOAD_TOO_LARGE及UNSUPPORTED_MEDIA_TYPE。
生成器按实际声明的错误码分组，约束每个响应的status和code，补齐公共响应头及401/429/处理中409的专有响应头。
创建接口不会被统一加入404，普通查询不会被统一加入409或上传相关错误。
公开接口等特殊场景可使用`includeCommon=false`，显式声明完整错误集合。

成功状态、响应DTO及Location/受理轮询头仍在所属Controller声明；公共X-Request-Id及Cache-Control由配置统一描述。
当前统一错误声明覆盖认证、账号、媒体、标签和内容模块的全部现有Controller；未声明`@ApiErrorResponses`的接口不会自动追加错误集合。
文档注解不参与权限判定或异常处理，业务流程、认证和事务仍按原模块执行。
CommonErrorDocumentationIT验证错误码隔离、响应头、成功schema及显式关闭默认集合。
测试资源`src/test/resources/contracts/api-v1-error-responses.json`保存从固定版本外部OpenAPI提取的已实现接口响应状态和错误码，记录来源与SHA-256，不依赖个人磁盘绝对路径。
测试按operationId逐项比较完整错误集合，并要求快照覆盖全部已生成接口；新增接口或正式调整契约时同步更新快照，不能通过修改公共默认集合绕过校验。
SessionAuthenticationService仍拒绝邮箱未验证的账号。BearerSessionFilter在认证失败边界仅为创建上传、创建内容保留EMAIL_VERIFICATION_REQUIRED，其余受保护请求映射为403 PERMISSION_DENIED，密码登录仍由公开登录流程处理；该映射不读取Swagger注解。
上传查询、取消事务中的邮箱复核同样返回PERMISSION_DENIED；认证前后状态变化均不能取得上传地址或修改上传状态。
全量验证日志为`.local-dev/controller-responses-full-check.log`。

2026-11-08验证：新增6项集中错误文档契约检查；`mvn clean verify`通过494项测试
（248项单元/Web、246项集成），0失败、0错误、0跳过。本次不涉及数据库迁移。

2026-11-08验证：完成认证、账号、媒体创建/查询/取消Controller迁移；`mvn verify`通过506项测试
（248项单元/Web、258项集成），0失败、0错误、0跳过。CommonErrorDocumentationIT扩展为18项检查。

2026-11-08验证：将EMAIL_VERIFICATION_REQUIRED纳入已认证接口公共403文档；`mvn verify`通过507项测试
（248项单元/Web、259项集成），0失败、0错误、0跳过。CommonErrorDocumentationIT扩展为19项检查，
新增全局403错误码一致性校验。

2026-11-08验证：新增Controller注解守卫、公开接口语义校验和后台统一授权入口；`mvn verify`通过517项测试
（254项单元/Web、263项集成），0失败、0错误、0跳过。CommonErrorDocumentationIT扩展为23项检查；
AdminAuthorizationManager未新增数据库迁移。

2026-11-08契约对齐验证：已按接口收敛邮箱未验证错误码，替代此前将EMAIL_VERIFICATION_REQUIRED加入公共403集合的做法。
按TDD验证认证错误映射、16个现有接口的完整错误契约，以及上传查询/取消在认证前后邮箱状态变化时的拒绝行为。
`mvn clean verify`通过533项测试（266项单元/Web、267项集成），0失败、0错误、0跳过，打包成功。
本次无新增生产类、依赖或迁移，原始OpenAPI契约未修改；完整日志为`.local-dev/contract-errors-full-check.log`。

2026-11-09请求校验修复：资料修改、上传完成、取消上传及标签修改统一将整数版本越界归为422，类型错误仍为400；
后台标签详情与修改拒绝未知查询参数，与后台列表共用入口校验。相关失败请求不读取或修改标签，不占用后台标签配额。
按TDD执行红、绿、重构后，`mvn clean verify`通过731项测试（316项单元/Web、415项集成），0失败、0错误、0跳过，打包成功。
未新增依赖或迁移，原始OpenAPI契约未修改；完整日志为`.local-dev/request-validation-full-check.log`。
