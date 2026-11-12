# 标签

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### 查询有效标签（公开）

```http
GET /api/v1/tags
```

游客可访问，不要求Bearer、请求体、查询参数或幂等键。成功返回200，data仅含items，元素复用Tag的id、name、description、status、version五个字段。
只读取ACTIVE标签，固定按名称、ID升序；不分页、不截断到20或100条，空结果返回items:[]。
复用TagQueryService和TagQueryTransactionService，公开分支只读标签表，不读取调用者账号或角色，不改变标签版本和时间。

GET /api/v1/tags在SecurityConfiguration和BearerSessionFilter中通过同一公开读取路径集合放行并跳过认证。
客户端附带过期、错误或受限账号的凭证不会导致401/403，也不触发会话认证；其他方法、后台标签和账号接口的认证权限保持原规则。
公开Controller显式关闭已认证接口的默认错误集合，Swagger仅声明本接口的400、422、429、500、503，响应保持X-Request-Id及Cache-Control:no-store。

独立配置app.tag.active-query，默认同来源120次/分钟，可通过TAG_ACTIVE_QUERY_IP_PER_MINUTE_LIMIT、TAG_ACTIVE_QUERY_REDIS_KEY_PREFIX覆盖。
使用独立tag-active-query来源计数，不使用用户配额，也不占用后台标签创建、查询或修改额度。
进入业务入口的每次调用都计数，包括重复读取、空列表和未知查询参数请求；超限优先429并提供Retry-After。
不接受page、status等未声明查询参数，返回400且不查询标签表；Redis无法确认限流结果或MySQL不可用返回503，不泄露内部细节。

每次查询读取当前已提交状态，不增加服务端标签缓存；后台停用提交后下一次查询不再包含该标签，重新启用后恢复显示。
列表用于展示和选择，后续内容/审核模块仍须在提交时重新校验标签状态。
新增Controller、响应DTO、配置和限流均归tag模块，复用现有表与查询组件，无新增依赖或迁移。
真实HTTP/MySQL/Redis检查公开访问、权限边界、全量排序、启停联动、只读性及并发限流；契约快照包含listActiveTags。
完整验证日志为`.local-dev/active-tags-full-check.log`。

2026-11-08验证：新增31项公开标签专项检查，契约快照新增listActiveTags，覆盖全部20个现有接口。
按TDD执行红、绿、重构后，`mvn clean verify`通过705项测试（298项单元/Web、407项集成），0失败、0错误、0跳过，打包成功。

### 管理员创建标签

`POST /api/v1/admin/tags`需要Bearer及16～128字符的Idempotency-Key，仅ADMIN对应的`tag:manage`权限可操作。
后台权限统一由`auth/security/AdminAuthorizationManager`按方法与路径判定；未登记的后台路径默认拒绝，业务事务仍复核ADMIN角色。
账号须已验证邮箱且为ACTIVE；HTTP安全链检查ADMIN，事务中再次锁定账号并共享锁读取当前角色，
防止认证后权限撤销仍写入。没有新增公开的管理员初始化或提权接口。

```json
{"name":"Java","description":"Java技术","status":"ACTIVE"}
```

只有name必填，去除首尾Unicode空白后长度为1～30个Unicode字符；保留内部空白和显示大小写。
description默认空字符串，最多200个字符且不trim；status默认ACTIVE，可显式指定DISABLED。
可选字段省略使用默认值，显式null返回422；类型错误、未知字段或无效枚举返回400，字段长度不符返回422。

成功201，data包含id/name/description/status/version，初始version=1；Location为`/api/v1/admin/tags/{tagId}`。
V9新增`community_tag`与`tag_creation_request`，V1～V8保持不变。
名称唯一索引采用`utf8mb4_0900_as_ci`：忽略大小写、保留重音区别，禁用标签仍占用名称。
并发同名创建由数据库唯一约束裁决，失败方409 TAG_NAME_CONFLICT；不依赖“先查再插入”保证唯一性。

#### 幂等与事务

作用域为调用者＋createTag＋key，保留24小时；只保存键摘要及规范化请求指纹，不保存原始幂等键。
指纹包括trim后的显示名称、原样description和解析默认值后的status；不同JSON字段顺序或省略默认值不影响重放。
同键同请求返回第一次的Tag快照、201及Location；标签后来修改也不会把旧创建结果重放成新版本。
同键不同请求409 IDEMPOTENCY_KEY_REUSED；处理中409 REQUEST_IN_PROGRESS，并提供Retry-After。
所有重放仍校验当前账号与管理员角色；过期键可用于新请求，但仍受名称唯一约束。

claim短事务采用READ_COMMITTED，锁顺序为账号→角色→幂等记录，预留60秒租约；同一调用者由账号行锁串行化。
此处避免默认隔离级别下两个调用者查询缺失幂等键后插入产生的间隙锁死锁；没有修改全局事务隔离级别。
Redis配额检查在SQL事务外执行，finish再次复核权限及租约令牌，标签与成功响应快照在同一事务提交。
快照写入失败则标签一起回滚；失败预留只由自己持有的令牌释放，租约到期可恢复，旧持有者不能覆盖新结果。

#### 限流与范围

`app.tag.creation`默认来源60次/分钟、管理员100次/小时，使用独立`{tag-create}`计数作用域。
沿用上传创建规则：进入新创建处理时计数，成功幂等重放免配额；新请求失败不退还已消费额度。
Redis无法确认结果或数据库不可用返回503；429提供正整数Retry-After，额度不与上传创建共享。
环境变量见infra/.env.example中的TAG_CREATION配置。

创建接口返回的Location可通过下述管理员详情GET读取；管理员分页列表和下述修改/停用PATCH也已实现。
专项验证涵盖管理员权限、认证后撤权、Unicode边界、大小写与重音、禁用名称冲突、幂等快照、过期及租约恢复、
真实MySQL并发与回滚、真实Redis小时/分钟窗口，以及生成的OpenAPI。全量日志为`.local-dev/tag-create-full-check.log`。

2026-11-08验证：本业务新增48项专项检查；`mvn clean verify`通过488项测试
（248项单元/Web、240项集成），0失败、0错误、0跳过，打包成功；V9迁移已执行。

### 管理员读取指定标签

```http
GET /api/v1/admin/tags/{tagId}
Authorization: Bearer <管理员凭证>
```

需要ADMIN对应的`tag:manage`权限。无需请求体、Idempotency-Key或expectedVersion。
不接受查询参数；未知或重复参数返回400及query字段提示，在查询标签和消耗后台查询配额前拒绝。
tagId为1～64位英文字母、数字、下划线或短横线；格式错误返回422及path/tagId字段提示，合法但不存在或大小写不匹配返回404。

成功返回200及`ApiResponse<Tag>`，data仅包含id、name、description、status、version。
读取数据库当前值，不使用创建幂等快照；可读取其他管理员创建的ACTIVE或DISABLED标签，不修改标签内容、状态、version和时间字段。
WRITE_BLOCKED管理员仍可读取；USER、MODERATOR、邮箱未验证或已撤销ADMIN角色的账号不能读取。
事务按账号、角色顺序取共享锁，复核authVersion、账号状态、邮箱与当前角色，再按主键读取标签。

`app.tag.query`默认来源120次/分钟、管理员60次/分钟，可用`TAG_QUERY_IP_PER_MINUTE_LIMIT`、`TAG_QUERY_USER_PER_MINUTE_LIMIT`及`TAG_QUERY_REDIS_KEY_PREFIX`覆盖。
计数作用域为独立`{tag-query}`，列表与详情共享配额，重复读取、空页和404计数，格式不合法的ID或分页参数在计数前拒绝；不占用标签创建或媒体接口配额。
429返回正整数Retry-After；MySQL、Redis不可用或限流结果无法确认时返回503，不泄露内部连接与查询信息。

Controller、Service接口与实现、事务、配置及限流均位于tag模块，复用TagView、TagMapper和V9已有表，不新增迁移或依赖。
真实HTTP/MySQL/Redis测试验证创建后通过Location读取、权限与认证后撤权、停用标签、只读性、独立限流及故障分支；OpenAPI契约快照已包含getAdminTag。
完整验证日志为`.local-dev/tag-query-full-check.log`。

2026-11-08验证：新增36项标签查询专项检查，固定契约快照新增getAdminTag，覆盖全部17个现有接口。
按TDD执行红、绿、重构后，`mvn clean verify`通过570项测试（277项单元/Web、293项集成），0失败、0错误、0跳过，打包成功。

### 管理员查询全部标签

```http
GET /api/v1/admin/tags?page=1&pageSize=20
Authorization: Bearer <管理员凭证>
```

需要ADMIN对应的`tag:manage`权限，包含所有管理员创建的ACTIVE和DISABLED标签。
复用详情查询的Controller、Service、事务、权限复核和限流器；新增响应DTO为TagPageView，不新增表、迁移、配置分组或依赖。
WRITE_BLOCKED管理员可读取；普通用户、审核员、邮箱未验证、禁用或已撤销ADMIN角色的账号被拒绝。

page默认1且必须为正整数；pageSize默认20，范围1～100。非整数或空字符串返回400，数值越界返回422，并提供query字段提示。
仅接受page、pageSize两个查询参数，各最多出现一次；未知或重复参数返回400，不接受客户端任意排序和筛选。
返回200及data中的items、page、pageSize、total；items的每条记录沿用Tag五个字段，不暴露创建者和内部时间字段。

固定按(created_at,id)倒序，ID打破时间并列。超出最后一页返回空items和真实total，不返回404；不静默跳回第一页。
页码不额外设定契约外上限，使用BigInteger计算偏移，越界空页不执行分页SQL，避免整数溢出。
先在短事务内复核账号与当前角色，再使用同一REPEATABLE_READ快照读取总数和当前页，避免并发创建使单次响应的total和items不一致。
读取不修改标签内容、状态、version或时间字段。

列表与详情共享app.tag.query的来源120次/分钟、管理员60次/分钟额度，可通过原TAG_QUERY环境变量覆盖。
重复查询、空页与详情404均计数，429提供Retry-After；参数非法请求不计查询配额，MySQL或Redis故障返回503。
真实HTTP/MySQL/Redis验证排序与边界、共享配额、认证后撤权、只读性、故障及并发快照；Swagger与固定契约快照覆盖listAdminTags。
完整验证日志为`.local-dev/tag-list-full-check.log`。

2026-11-08验证：新增35项列表专项检查，契约快照新增listAdminTags，覆盖全部18个现有接口。
按TDD执行红、绿、重构后，`mvn clean verify`通过606项测试（277项单元/Web、329项集成），0失败、0错误、0跳过，打包成功。

### 管理员修改或停用标签

```http
PATCH /api/v1/admin/tags/{tagId}
Authorization: Bearer <管理员凭证>
Content-Type: application/json

{"expectedVersion":1,"status":"DISABLED"}
```

需要ADMIN对应的tag:manage权限，账号必须ACTIVE且邮箱已验证；事务内重新检查authVersion和当前角色。
不要求Idempotency-Key；成功返回200及当前Tag五个字段。停用只更新status，不删除记录，其他管理员也能修改平台标签。
可提交name、description、status中的任意一个或多个字段；必须携带正整数expectedVersion，且至少提供一个待修改字段。
未提交字段保持原值，显式null返回422；名称沿用创建规则去除Unicode首尾空白、1～30字符，说明最多200字符且不trim，空字符串可清空说明。
状态为ACTIVE或DISABLED，可重新启用；未知字段、JSON类型错误或枚举非法返回400。
expectedVersion整数值超出1～2147483647返回422及body字段提示；不接受查询参数，未知或重复参数在标签读写和限流计数前返回400。

锁顺序为账号共享锁、当前角色共享锁、目标标签FOR UPDATE行锁；更新SQL仍携带id与expectedVersion条件。
版本不匹配返回409 RESOURCE_VERSION_CONFLICT及授权后读取的currentVersion，所有字段不变。
先核对版本，再判断同值请求；版本匹配但无实际变化返回200，version与updated_at不变。真实修改一次只递增一版，版本达到整数上限时拒绝继续递增。
名称继续忽略大小写、区分重音判重，启用和停用标签均占用名称；修改自身显示大小写属于实际变化。重名返回409 TAG_NAME_CONFLICT，整次修改回滚。
两个管理员并发修改同一版本，只有一方成功；不同标签并发抢占同名由数据库唯一约束裁决。数据库失败返回503，已执行的SQL更新仍整体回滚。

独立配置app.tag.update：默认来源60次/分钟、管理员100次/小时，环境变量为TAG_UPDATE_IP_PER_MINUTE_LIMIT、TAG_UPDATE_USER_HOURLY_LIMIT、TAG_UPDATE_REDIS_KEY_PREFIX。
使用独立tag-update计数域，不占用创建或查询配额；同值、版本冲突、合法但不存在的标签请求均计数，参数非法请求在计数前拒绝。429提供Retry-After，Redis无法确认结果时返回503。
修改不改变原创建幂等快照，原创建键成功重放仍免创建配额；读取当前信息应使用详情或列表GET。

实现、配置、限流、DTO与测试全部归tag模块，复用V9表、TagView、现有认证与Lua，不新增依赖或迁移。
本接口持久化启停状态；内容提交审核时重新校验标签ACTIVE，历史标签展示需由后续相应接口按契约接入。
真实HTTP/MySQL/Redis验证事务回滚、并发版本与重名竞争、PATCH语义及独立限流；Swagger及契约快照覆盖updateTag。
完整验证日志为`.local-dev/tag-update-full-check.log`。

2026-11-08验证：新增66项修改/停用专项检查，契约快照新增updateTag，覆盖全部19个现有接口。
按TDD执行红、绿、重构后，`mvn clean verify`通过673项测试（288项单元/Web、385项集成），0失败、0错误、0跳过，打包成功。

## 调用链阅读顺序

### 创建标签怎样阅读

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

### 管理员标签详情与列表怎样阅读

1. `tag/controller/TagQueryController.java`：GET /api/v1/admin/tags/{tagId}返回TagView，GET /api/v1/admin/tags返回TagPageView；仅详情在公共错误外声明RESOURCE_NOT_FOUND。
   `tag/controller/TagQueryParameters.java`集中校验后台标签查询参数：列表仅允许单个page/pageSize，详情和修改不接受查询参数；非法参数在调用业务服务和计数前返回400。
2. `auth/security/AdminAuthorizationManager.java`：为两个GET分别登记ADMIN，未登记后台路由仍默认拒绝。
3. `tag/service/TagQueryService.java`与`service/impl/TagQueryServiceImpl.java`：身份、路径及分页参数校验，共享查询配额、事务调用及依赖错误转换。
4. `tag/service/transaction/TagQueryTransactionService.java`：共享账号与角色复核；详情按主键读取，列表按创建时间和ID倒序，用同一REPEATABLE_READ快照读取总数与分页记录。
5. `tag/config/TagQueryProperties.java`、Configuration及`ratelimit/TagQueryRateLimiter.java`：列表与详情共享app.tag.query来源/管理员分钟窗口，使用独立tag-query键，不与创建配额混用。
6. `tag/dto/TagPageView.java`只返回items、page、pageSize、total，items复用TagView；复用现有MyBatis-Plus分页插件，BigInteger页码越界直接返回空页。
7. `tag/service/TagQueryFlowIT`、TagListFlowIT及config/ratelimit测试覆盖真实HTTP、MySQL、Redis；config/CommonErrorDocumentationIT按已同步的契约快照核对两个接口的完整错误集合。

查询返回当前五个Tag字段，不修改标签状态、版本或时间；WRITE_BLOCKED管理员可读，禁用账号和撤权请求被拒绝。复用已有V9表，无新增迁移。

### 管理员修改或停用标签怎样阅读

1. `tag/controller/TagUpdateController.java`：PATCH /api/v1/admin/tags/{tagId}，返回200及TagView，额外声明404、版本/名称冲突和请求体错误。
2. `tag/dto/TagUpdateRequest.java`：严格JSON、字段存在标记、Unicode长度与可选字段；expectedVersion的Schema显式绑定Integer，避免JsonNode严格setter影响文档类型。
   标签修改、资料修改、上传完成请求先用BigInteger保留版本原值，再校验1～2147483647范围，超出范围返回422；验证通过后业务层仍使用Integer。取消上传的查询参数采用相同范围规则。
3. `tag/service/TagUpdateService.java`与`service/impl/TagUpdateServiceImpl.java`：身份、路径及PATCH输入校验、独立限流、事务调用和依赖错误转换。
4. `tag/service/transaction/TagUpdateTransactionService.java`：账号→角色→标签固定锁序；先授权和版本校验，再处理同值与真实修改。重名和数据库异常使整次事务回滚。
5. `tag/mapper/TagMapper.java`新增标签FOR UPDATE查询及仅更新所提供字段的条件SQL；id和expectedVersion必须匹配，版本与updated_at随真实修改一起更新。
6. `tag/config/TagUpdateProperties.java`、Configuration及`ratelimit/TagUpdateRateLimiter.java`：app.tag.update独立来源分钟、管理员小时窗口，复用已有Lua和独立tag-update键。
7. `tag/service/TagUpdateFlowIT`、config/ratelimit测试验证真实HTTP、MySQL、Redis及SQL屏障并发；config/CommonErrorDocumentationIT按已同步快照核对updateTag。

停用保留记录，允许重新启用和管理员之间管理；不更新原创建快照、不增加依赖或迁移。新审核禁止引用停用标签的消费逻辑由后续内容/审核模块实现。

### 公开有效标签怎样阅读

1. `tag/controller/PublicTagController.java`：GET /api/v1/tags，返回200及TagListView，仅含items；显式关闭已认证公共错误集合。
2. `auth/security/BearerSessionFilter.PUBLIC_READ_PATHS`与SecurityConfiguration：仅按GET和精确路径公开访问并跳过Bearer认证，错误或失效凭证不影响公开读取；后台路径不受影响。
3. `tag/service/TagQueryService.java`与`service/impl/TagQueryServiceImpl.java`：复用查询入口，公开分支先独立来源限流，再拒绝未知查询参数，映射依赖故障为503。
4. `tag/service/transaction/TagQueryTransactionService.activeTags`：只读事务过滤ACTIVE，按名称和ID升序，复用TagView转换，不调用后台授权方法。
5. `tag/config/ActiveTagQueryProperties.java`、Configuration及`ratelimit/ActiveTagQueryRateLimiter.java`：app.tag.active-query来源分钟窗口，不使用用户配额，与后台计数隔离。
6. `redis/tag-active-query-rate-limit.lua`：一个来源计数键，原子检查、计数和设置60秒到期；无法确认执行结果时拒绝放行。
7. `tag/service/ActiveTagFlowIT`及config/ratelimit测试覆盖真实HTTP、MySQL、Redis、后台启停联动与并发配额；CommonErrorDocumentationIT按快照核对公开错误集合。

公开查询不分页、不缓存标签数据；停用标签不返回。账号状态不改变该公开接口的可读性，所有后台安全检查继续在原用例执行。
