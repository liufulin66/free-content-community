# 内容创作与工作区

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### 创建内容及首个草稿

```http
POST /api/v1/creator/contents
Authorization: Bearer <用户凭证>
Idempotency-Key: <16～128字符>
Content-Type: application/json

{"kind":"ARTICLE","title":"事务基础","bodyMarkdown":"# 事务基础"}
```

需要邮箱已验证、账号ACTIVE且拥有USER角色，沿用CurrentUser.canCreate的资格规则；管理员和审核员保留USER角色时同样可以创作。
返回201、ContentWorkspace及Location；内容初始ACTIVE/UNPUBLISHED、version=1，首个修订DRAFT、revisionNumber=1，未发布、未审核且未删除。
ARTICLE与VIDEO创建后不可互转。VIDEO草稿可先不关联视频，这不代表视频上传或处理已实现。

标题和正文默认空字符串，图片和标签默认空数组；上述字段显式null返回422，封面和视频允许null。
标题最多120、正文最多100000个Unicode码点；图片最多50项、标签最多5项，数组元素不得重复，ID按契约校验。
类型错误、未知字段或查询参数返回400；字段约束、重复ID、ARTICLE关联视频或正文图片清单不一致返回422。
正文原文不trim、不渲染HTML。CommonMark Java 0.32.0将Markdown解析成语法树，仅统计真实图片节点，忽略代码块、行内代码和转义示例；支持引用式图片。
正文图片必须使用asset:ID，引用集合与imageAssetIds一致，列表顺序保留。该解析不执行HTML，后续渲染仍必须禁用原生HTML执行。

素材不存在或非本人统一404；封面和正文图片必须CONTENT_IMAGE，视频必须CONTENT_VIDEO，用途错误422。
草稿可以暂存本人WAITING_UPLOAD、PROCESSING、FAILED或READY素材及已存在的停用标签；READY和ACTIVE的发布门槛由后续提交审核接口检查。
数据库通过引用表和外键保留素材、标签关联，同一内容至多一个DRAFT/IN_REVIEW；工作修订指针不能指向其他内容的修订。

幂等键按用户和createContent隔离，有效期24小时；过期键再次使用时替换旧记录。使用规范化默认字段后的请求指纹，成功重放返回首次业务结果快照，不返回后续编辑状态。
同键不同请求409 IDEMPOTENCY_KEY_REUSED；处理中409 REQUEST_IN_PROGRESS并提供Retry-After。预留租约60秒，过期可接管，旧租约令牌不能回写或删除新结果。
事务锁序为账号、角色、幂等记录、按ID排序的素材；claim使用局部READ_COMMITTED避免缺失幂等键的间隙锁竞争。
Redis计数位于领取和最终写入两个短事务之间。finish再次检查账号资格、租约与引用，内容、草稿、引用和幂等快照一次提交；数据库故障整体回滚并返回503。
释放预留记录失败时保留PROCESSING记录，后续按租约恢复，不能把预留记录当作已创建内容。

独立配置app.content.creation，默认来源60次/分钟、用户100次/小时；环境变量CONTENT_CREATION_IP_PER_MINUTE_LIMIT、CONTENT_CREATION_USER_HOURLY_LIMIT及CONTENT_CREATION_REDIS_KEY_PREFIX可覆盖。
Redis使用独立{content-create}作用域，成功幂等重放免计配额，格式与语义输入校验失败不计数；进入引用校验的业务失败可计数。
Redis故障或执行结果无法确认返回503；429提供正整数Retry-After。

V10新增community_content、content_revision、content_revision_asset、content_revision_tag、content_creation_request五张表，保留V1～V9。
Controller、DTO、Service接口/impl、事务、配置、限流、校验、Mapper及测试全部归content模块；仅复用auth安全能力和现有素材、标签的数据访问能力。
固定版本错误契约快照已覆盖createContent，合计21个接口；原始OpenAPI文件未改动。
创建响应的Location已接入本人工作区GET，查询返回数据库当前状态；草稿保存、提交审核和审批发布状态已实现，公开内容查询仍待后续实现。

2026-11-09验证：按TDD执行红、绿、重构后，`mvn clean verify`通过839项测试
（379项单元/Web、460项集成），0失败、0错误、0跳过，打包成功。新增106项content模块专项检查，并补充认证映射和全局契约检查。
真实HTTP/MySQL/Redis验证首次创建、ARTICLE/VIDEO草稿、Markdown引用、当前资格复核、幂等重放与指纹冲突、并发、独立配额、事务回滚和租约令牌恢复；V10已执行。
完整验证日志为`.local-dev/content-create-full-check.log`。

### 读取本人内容工作区

```http
GET /api/v1/creator/contents/{contentId}
Authorization: Bearer <用户凭证>
```

成功200并返回ContentWorkspace，与创建接口复用DTO。包含本人ACTIVE和DELETED内容及全部发布状态；管理员、审核员也只能读取自己的工作区，其他人的内容与不存在的内容统一404。
邮箱须已验证并保有USER角色，WRITE_BLOCKED允许读取，DISABLED禁止。Bearer认证后，在事务内再次用账号、角色共享锁复核authVersion、状态、邮箱和当前角色；邮箱未验证按本接口契约返回PERMISSION_DENIED。

读取community_content当前版本和指针，再按contentId加载workingRevision和publishedRevision及各自引用；不读取创建幂等快照，不回退到最近一条历史修订。
空指针返回null，空图片/标签列表返回[]，引用按position排序，保留停用标签ID及素材引用。
工作稿只接受DRAFT/IN_REVIEW，发布指针只接受APPROVED；WITHDRAWN、TAKEN_DOWN或删除后的历史发布指针不表示仍可公开访问。
指针关联缺失、状态不一致或单值封面/视频关联重复时返回503，避免把异常数据伪装成空稿或正常工作区。
查询不修改内容、修订、引用、时间、版本和创建幂等记录。

ContentWorkspaceTransactionService使用readOnly=true及REPEATABLE_READ，同一快照组装内容、修订和引用，避免并发提交导致跨版本拼接；不对内容加写锁。
来源沿用Tomcat现有可信代理解析后的request.getRemoteAddr，不在业务层自行解析X-Forwarded-For。
未知或重复查询参数返回400，非法contentId返回422及path/contentId提示，均在访问内容和消耗查询配额前拒绝。

列表与详情共享app.content.query，来源120次/分钟、用户60次/分钟；环境变量CONTENT_QUERY_IP_PER_MINUTE_LIMIT、CONTENT_QUERY_USER_PER_MINUTE_LIMIT和CONTENT_QUERY_REDIS_KEY_PREFIX可覆盖。
作用域为{content-query}，与创建额度隔离。重复读取、合法ID的404均计数；429提供正整数Retry-After。Redis结果无法确认或MySQL故障返回503，不泄露连接和SQL细节。
复用V10，无新增依赖和迁移。固定版本错误契约已覆盖getMyContentWorkspace，合计22个接口，原始OpenAPI未改动。

2026-11-09验证：按TDD执行红、绿、重构后，`mvn clean verify`通过892项测试
（392项单元/Web、500项集成），0失败、0错误、0跳过，打包成功。新增51项工作区查询及限流专项检查，并补充认证和全局契约检查。
真实HTTP/MySQL/Redis验证创建Location读取、当前值、双修订及有序引用、删除内容、归属、资格复核、并发快照、只读性、故障及独立限流。
完整验证日志为`.local-dev/content-workspace-full-check.log`。

### 保存当前可编辑草稿

```http
PATCH /api/v1/creator/contents/{contentId}/draft
Authorization: Bearer <用户凭证>
Content-Type: application/json

{"expectedVersion":1,"title":"更新标题"}
```

不要求Idempotency-Key；返回200及保存后的ContentWorkspace。expectedVersion取最近工作区的内容聚合版本，范围1～2147483647。
JSON类型错误、未知字段或查询参数返回400；整数版本越界、缺失版本、仅提交版本、字段范围不符或非法显式null返回422。
标题、正文、图片和标签可省略并保留原值，不接受null；空字符串清空标题/正文，空数组整体清空相应列表。封面和视频可用null清空，kind、作者、生命周期和发布状态不可由请求修改。
字符串不trim，标题最多120、正文最多100000个Unicode码点；图片最多50、标签最多5，元素不得重复。

账号须ACTIVE、邮箱已验证且当前保有USER角色，WRITE_BLOCKED连同值保存也禁止。其他用户或不存在的内容统一404，不返回对方版本。
先复核归属和expectedVersion，旧版本409 CONTENT_VERSION_CONFLICT并提供currentVersion；即使目标值已相同，也不能绕过旧版本冲突。
DELETED内容409 CONTENT_NOT_EDITABLE，需先恢复；没有工作稿、存在待审核标记或当前修订非DRAFT时409 DRAFT_NOT_EDITABLE。
同值请求在版本和状态检查后返回200，内容版本、修订序号和各自时间均不改变；真实变化只递增一次内容version，更新内容和当前草稿的updatedAt，保留修订ID、revisionNumber和createdAt。
达到Integer.MAX_VALUE时同值请求仍成功，真实修改409，禁止溢出。PUBLISHED、WITHDRAWN、TAKEN_DOWN下合法DRAFT可继续保存，发布快照、公开指针及发布状态不变，不解除强制下架。

PATCH先与当前DRAFT合并，再复用CommonMark校验正文asset:图片引用与最终imageAssetIds集合一致。数组顺序保留，调整顺序属于真实修改。
ContentReferenceValidator由创建和保存共用：素材须本人所有且用途正确，标签须存在。素材未READY、FAILED或标签已停用仍允许暂存；提交审核通过ContentReviewSubmissionValidator另行检查READY和ACTIVE。
真实修改只替换发生变化的引用用途/数组；清空正文图片时通常需同时提交bodyMarkdown和imageAssetIds，避免留下不一致引用。

调用链为输入校验→短事务预检→独立Redis计数→写事务重新复核与保存。
预检读取并校验合并后的完整草稿，不修改业务数据；格式、字段和合并校验失败不计配额，合法404与业务409按调用计数。预检释放数据库锁后再访问Redis。
写事务再次按账号、角色、内容、当前修订、排序后的素材加锁；重新检查资格、版本、DRAFT状态及引用，防止预检后发生撤权或并发修改。
更新SQL仍携带作者、expectedVersion、当前工作指针及DRAFT条件。内容版本、草稿文本、引用和返回工作区的读取处于同一写事务，任一步失败整体回滚。
不更新创建幂等快照，不创建新修订、审核或处理任务，不触发发布或媒体READY推进。

app.content.draft-save默认来源120次/分钟、用户60次/分钟，使用独立{content-draft-save}作用域；同值、版本冲突请求计数，与创建和查询额度隔离。
CONTENT_DRAFT_SAVE_IP_PER_MINUTE_LIMIT、CONTENT_DRAFT_SAVE_USER_PER_MINUTE_LIMIT、CONTENT_DRAFT_SAVE_REDIS_KEY_PREFIX可覆盖配置。来源沿用Tomcat可信代理解析后的request.getRemoteAddr。
429提供正整数Retry-After；Redis无法确认计数或数据库故障返回503，不泄露连接或SQL细节。
复用V10，无新增迁移和依赖。固定错误契约新增editContentDraft，覆盖23个接口；原始OpenAPI未改动。

2026-11-09验证：按TDD执行红、绿、重构后，`mvn clean verify`通过1005项测试
（449项单元/Web、556项集成），0失败、0错误、0跳过，打包成功。新增111项草稿保存专项检查，并补充认证映射和全局契约检查。
真实HTTP/MySQL/Redis验证字段存在语义、合并校验、同值、版本冲突、引用替换和清空、权限重检、冻结快照、并发单一成功、事务回滚及独立配额。
完整验证日志为`.local-dev/content-draft-save-full-check.log`。

### 查询本人创作及回收站列表

```http
GET /api/v1/creator/contents?page=1&pageSize=20&lifecycle=ACTIVE
Authorization: Bearer <用户凭证>
```

返回200及WorkspacePage：items、page、pageSize、total；items复用完整ContentWorkspace，包括工作稿、发布稿及各自引用，不是摘要。
仅查询认证上下文的本人数据，管理员和审核员也不扩大范围。默认lifecycle=ACTIVE；回收站传DELETED。可再按publicationStatus（UNPUBLISHED/PUBLISHED/WITHDRAWN/TAKEN_DOWN）及kind（ARTICLE/VIDEO）组合筛选，条件按AND生效。
无匹配数据或超出末页返回200、空items和实际total，不返回404。工作/发布指针为空时保持null，不从已终结历史稿补造标题，不增加契约外顶层title。

page默认1且必须为正整数，不加契约外上限；pageSize默认20、范围1～100。使用BigInteger计算偏移，超末页直接返回空页，不转换或执行溢出的分页偏移。
固定按updatedAt倒序、ID倒序，不允许客户端任意排序。未知、重复查询参数或无法解析的分页/枚举返回400；整数分页值超出范围返回422并提供query字段提示。非法参数不消耗查询配额。

邮箱须已验证且当前保有USER角色，WRITE_BLOCKED允许读取、DISABLED禁止。短事务先以账号、角色共享锁复核authVersion、状态、邮箱和角色，再查询本人内容。
COUNT、页面记录、所有工作/发布修订和引用处于同一readOnly/REPEATABLE_READ快照，避免并发编辑或新增导致总数、页面成员及正文跨版本拼接。
页面内批量读取修订、素材引用和标签引用，不逐条调用详情、不逐条计数；关联损坏返回503，不伪造空稿或部分成功页。查询不修改内容、修订、引用、时间、版本或创建幂等记录。

列表和详情共用app.content.query及现有{content-query}作用域，来源120次/分钟、用户60次/分钟，配置可覆盖；重复查询和空页都计数，与创建和草稿保存额度隔离。
429提供正整数Retry-After；Redis无法确认额度或MySQL故障返回503，不泄露内部连接和SQL信息。来源沿用Tomcat可信代理解析后的request.getRemoteAddr。
扩展既有查询Controller、Service、事务与Mapper，新增WorkspacePage；不新增表、迁移、依赖或限流配置。固定版本错误契约已覆盖listMyContents，合计24个接口，原始OpenAPI未修改。
本接口读取回收站状态，删除及恢复接口仍待后续实现。

2026-11-09验证：按TDD执行红、绿、重构后，`mvn clean verify`通过1070项测试
（481项单元/Web、589项集成），0失败、0错误、0跳过，打包成功。新增63项本人列表专项检查，并补充认证映射和全局契约检查。
真实HTTP/MySQL/Redis验证本人范围、组合筛选、创作区/回收站、更新时间排序和ID并列排序、分页/超大页码、批量修订及引用、当前权限、并发快照、只读性、依赖故障和共享配额。
完整验证日志为`.local-dev/content-list-full-check.log`。

### 预览本人修订引用的媒体

```http
GET /api/v1/creator/contents/{contentId}/revisions/{revisionId}/media/{assetId}/access
Authorization: Bearer <用户凭证>
```

成功200并返回MediaAccess，仅含assetId、url、expiresAt、contentType。业务响应和签名对象响应均no-store；对象URL按GET使用，支持Range，不附带业务Bearer。
默认签名300秒，expiresAt与SigV4编码的整秒签发时间一致。过期后重新调用业务接口；篡改目标、方法、有效期或响应内容类型不能沿用原签名。

邮箱须已验证并当前保有USER角色，WRITE_BLOCKED可读、DISABLED禁止。管理员和审核员在此仍只能访问本人内容，其他人的内容或素材、错误的修订所属关系、未被该修订引用或用途不匹配的素材统一404。
按指定修订的实际引用表授权，封面/正文图片要求CONTENT_IMAGE，视频要求VIDEO内容和CONTENT_VIDEO。支持同一图片同时作为封面/正文，以及历史APPROVED/REJECTED/CANCELLED修订和本人回收站内容，不把请求限制为当前工作指针。
先验证归属和引用，再检查状态；WAITING_UPLOAD、PROCESSING、FAILED均409 MEDIA_NOT_READY。READY但缺少实际元数据或稳定读取绑定时503，不退回原始source对象。
非法路径ID返回422，未知/重复查询参数返回400，均在签名配额与业务查询前拒绝；没有请求体、查询参数或幂等键要求。

V11为media_asset新增ready_object_key、ready_object_etag、ready_storage_id，可空且要求成组填写；原有记录及状态不回填、不提升为READY。
结果key格式为private/{ownerId}/{assetId}/ready/{独立标识}，必须与所属素材、存储指纹匹配。后续worker完成真实格式、校验和及必要尺寸检查后，须写入新的结果key并原子绑定READY，结果key不得覆写复用；业务不会为ready路径签发PUT地址。
使用独立结果对象，不改变MinIO桶版本控制；原始上传PUT地址仍可能有效，但覆盖source不会改变预览的结果对象。
StorageProperties.identity沿用既有上传指纹算法，覆盖endpoint、bucket、region及凭据；配置改变时旧绑定拒绝读取，需要可信处理流程重新绑定。

调用链为路径校验→独立限流→短只读事务授权及读取绑定→事务外MinIO HEAD→最终事务复核、GET签名与最小审计。
HEAD验证结果对象真实存在、ETag和大小与绑定一致，连接超时3秒、读写5秒、调用总超时10秒；HEAD不承担真实文件格式校验。
最终事务重新检查账号、角色、内容、修订、引用和素材状态；读取绑定须与HEAD前完全一致，期间撤权、移除引用、失败或绑定变化均不发放旧地址。
账号、角色、内容、修订、引用及素材通过共享锁保护。MediaReadSigner只做本地SigV4签名，在事务内不访问存储网络，签名固定GET、对象路径和响应MIME/no-store/inline覆盖头。
content_media_preview_audit只记录成功签发的最小事实：主体、内容/修订/素材、动作、requestId、签发和到期时间，不保存URL、凭据、对象路径或原始正文；审计入库失败返回503且不返回签名。当前没有审计查询/修改接口。
存储缺失或绑定/元数据不一致、数据库故障、签名失败均503，不公开内部路径、SQL或连接详情；DTO的toString也不输出URL。

app.content.media-preview独立来源120次/分钟、用户60次/分钟，与普通内容查询、创建和保存隔离；重复成功申请、合法404和409都计数，429提供正整数Retry-After。
CONTENT_MEDIA_PREVIEW_READ_URL_TTL_SECONDS、CONTENT_MEDIA_PREVIEW_IP_PER_MINUTE_LIMIT、CONTENT_MEDIA_PREVIEW_USER_PER_MINUTE_LIMIT、CONTENT_MEDIA_PREVIEW_REDIS_KEY_PREFIX可覆盖；TTL校验1～604800秒，默认仍为300秒。
已发出的对象签名在到期前仍有可访问窗口，移除引用、退出或后续限制账号不会立即撤销既有URL，已下载文件也无法收回；业务接口后续申请始终重新授权。

授权Controller、Service、事务、配置、限流、引用查询和审计均归content；通用MediaAccess、对象核验、GET签名及存储配置归media，auth仍只提供安全上下文。
本轮只实现私有预览和就绪对象绑定，不实现校验worker、READY推进、转码或视频上传。测试构造已就绪结果对象及元数据并使用真实MinIO验证读取，不代表这些后台处理已实现。
原始OpenAPI未修改，固定错误契约已覆盖getDraftMediaAccess，合计25个接口。

2026-11-09验证：按TDD执行红、绿、重构后，`mvn clean verify`通过1163项测试
（519项单元/Web、644项集成），0失败、0错误、0跳过，打包成功。新增91项预览、配置、限流和存储签名专项检查，并补充认证及全局契约检查。
真实HTTP/MySQL/Redis/MinIO验证双重归属与引用、历史/回收站、READY绑定、事务外HEAD和最终重检、旧PUT与结果对象隔离、实际GET/Range、过期及篡改拒绝、独立额度和最小审计失败；V11已执行。
完整验证日志为`.local-dev/media-preview-full-check.log`。

## 调用链阅读顺序

### 创建内容与首个草稿怎样阅读

1. `content/workspace/controller/ContentCreationController.java`：接收POST /api/v1/creator/contents，拒绝未知查询参数，返回201、ContentWorkspace和Location。
2. `content/workspace/dto/CreateContentRequest.java`：严格类型、字段默认值、null和Unicode长度校验；`ContentWorkspace`、`RevisionView`只返回契约字段。`model/ContentKind`定义创建后固定的类型。
3. `content/validation/ContentDraftValidator.java`：CommonMark语法树识别真实图片，核对asset:引用集合、重复ID及ARTICLE/VIDEO约束，不执行HTML。
4. `content/workspace/service/ContentCreationService.java`与`service/impl/ContentCreationServiceImpl.java`：输入、指纹、领取幂等预留、成功重放免计配额、独立限流、最终写入和失败释放。
5. `content/workspace/service/transaction/ContentCreationTransactionService.java`：claim与finish分别复核当前账号和角色，局部READ_COMMITTED领取租约，finish核对租约令牌和引用并原子提交；私有方法分别处理重放快照和引用持久化。
6. `content/workspace/mapper/ContentCreationMapper.java`处理内容、修订和引用；`ContentCreationRequestMapper`与`entity/ContentCreationRequestRecord`处理幂等预留、续租、快照及令牌保护的完成/释放。
7. `content/workspace/config/ContentCreationProperties.java`及Configuration定义app.content.creation，并补充OpenAPI默认值；`ratelimit/ContentCreationRateLimiter.java`复用现有Lua与独立content-create作用域。
8. `V10__content_creation.sql`新增内容、修订、素材引用、标签引用和创建幂等表，外键限制跨内容指针和悬空引用，唯一约束限制单个工作稿。
9. `content/controller`、`validation`、`config`、`ratelimit`及`service`对应测试目录；ContentCreationFlowIT验证真实HTTP/MySQL/Redis、并发、回滚和租约恢复，全局错误契约测试覆盖createContent。

创建用例只扩展auth的邮箱错误码映射；内容业务全部留在content。工作区GET、草稿保存、提交审核和审批发布状态已实现，公开内容查询及媒体READY推进仍待后续实现。

### 读取本人内容工作区怎样阅读

1. `content/workspace/controller/ContentWorkspaceController.java`：GET /api/v1/creator/contents/{contentId}，拒绝未知查询参数，复用ContentWorkspace及公共错误声明。
2. `content/workspace/service/ContentWorkspaceService.java`与`service/impl/ContentWorkspaceServiceImpl.java`：身份和路径校验、独立查询配额、事务调用以及数据库故障转换。
3. `content/workspace/service/transaction/ContentWorkspaceTransactionService.java`：账号、角色共享锁复核当前权限；readOnly/REPEATABLE_READ快照读取内容、工作稿、发布稿及引用，检查指针状态及单值关联一致性。
4. `content/mapper/ContentReadMapper.java`：查询投影记录，根查询同时约束contentId与authorId，修订再次约束contentId，素材和标签按position排序；不读取创建快照。
5. `content/config/ContentQueryProperties.java`、Configuration及`ratelimit/ContentQueryRateLimiter.java`：列表和详情共用app.content.query来源/用户分钟窗口，复用现有Lua与content-query作用域。
6. `content/service/ContentWorkspaceFlowIT`覆盖真实HTTP/MySQL/Redis、所有权、删除状态、双修订、当前数据、快照并发、故障、独立限流及契约；config/ratelimit测试验证配置和原子限流。

复用V10与现有响应DTO，不新增依赖或迁移。创建接口的Location现在可直接读取；草稿保存已实现，删除恢复、审核和发布仍由后续用例实现。

### 保存当前可编辑草稿怎样阅读

1. `content/workspace/controller/ContentDraftSaveController.java`：PATCH /api/v1/creator/contents/{contentId}/draft，拒绝未知查询参数，复用ContentWorkspace与公共错误响应。
2. `content/workspace/dto/EditDraftRequest.java`：BigInteger保留版本输入供范围校验；Setter标记字段存在，区分省略、null和空值。只允许已定义的修改字段。
3. `content/workspace/service/ContentDraftSaveService.java`与`service/impl/ContentDraftSaveServiceImpl.java`：输入校验、预检、独立计数、重新校验并保存，转换数据库故障；不使用幂等键缓存或重放。
4. `content/workspace/service/transaction/ContentDraftSaveTransactionService.java`：preflight与save各自持有短事务，复用inspect完成账号、角色、版本、状态、合并和引用检查；Redis位于两个事务之间。真实保存才递增版本并按变化替换引用，同值保持时间。
5. `content/validation/ContentDraftValidator.java`复用Markdown语法树和去重规则；`ContentReferenceValidator.java`供创建与保存共用素材归属/用途及标签存在性检查。
6. `content/mapper/ContentLockMapper.java`提供所属内容与当前修订行锁；`content/workspace/mapper/ContentDraftSaveMapper.java`负责版本条件更新、仅DRAFT文本更新以及引用替换，禁止修改已发布修订或重新分配工作指针。
7. `content/workspace/config/ContentDraftSaveProperties.java`及Configuration定义app.content.draft-save；`ratelimit/ContentDraftSaveRateLimiter.java`复用分钟窗口Lua，使用独立content-draft-save作用域。
8. `content/controller/ContentDraftSaveEndpointTest`及`service/ContentDraftSaveFlowIT`验证类型/存在标记、同值、版本、权限、并发、冻结快照、回滚、引用和契约；config/ratelimit对应测试验证配置与原子额度。

保存接口复用V10与工作区读取组件，不新增迁移和依赖；读取返回值加入现有写事务，不额外占用工作区查询额度。提交审核见第26节、审批发布状态见第31节；新建后续修订、删除恢复和公开查询仍待后续实现。

### 本人创作及回收站分页怎样阅读

1. `content/workspace/controller/ContentWorkspaceController.java`新增GET /api/v1/creator/contents，仅允许单个page/pageSize/lifecycle/publicationStatus/kind；与详情复用查询参数检查。
2. `content/workspace/service/ContentWorkspaceService.java`与`service/impl/ContentWorkspaceServiceImpl.java`新增listMyContents，校验分页和枚举，默认ACTIVE，复用详情查询额度与故障转换。
3. `content/workspace/service/transaction/ContentWorkspaceTransactionService.java`新增list：同一只读REPEATABLE_READ快照复核资格、COUNT、分页及批量加载修订/引用，与详情共用投影和一致性检查；空指针保留null。
4. `content/mapper/ContentReadMapper.java`：COUNT和页面SQL复用本人及筛选条件，固定updated_at/id倒序；批量读取页内工作稿和发布稿，限制修订所属contentId，引用按position排序，避免N+1查询。
5. `content/workspace/dto/WorkspacePage.java`返回items/page/pageSize/total，items复用ContentWorkspace；BigInteger页码超末页直接返回空页，不加契约外上限。
6. `content/controller/ContentListEndpointTest`覆盖参数边界、重复/未知字段与计数前拒绝；`content/service/ContentListFlowIT`覆盖真实HTTP/MySQL/Redis、回收站和组合筛选、稳定排序、批量引用、权限、并发快照、故障与共享额度。

复用app.content.query、V10与响应工作区模型；不新增配置组、依赖或迁移。列表只读取回收站状态，删除恢复仍待后续用例实现。

### 本人修订媒体预览怎样阅读

1. `content/workspace/controller/ContentMediaPreviewController.java`：GET /api/v1/creator/contents/{contentId}/revisions/{revisionId}/media/{assetId}/access，拒绝未知查询参数，返回MediaAccess，不要求幂等键。
2. `content/workspace/service/ContentMediaPreviewService.java`与`service/impl/ContentMediaPreviewServiceImpl.java`：路径、独立额度、prepare、事务外对象核验、finish及数据库故障转换。
3. `content/workspace/service/transaction/ContentMediaPreviewTransactionService.java`：两次复核当前资格、内容/修订/素材归属、真实引用及用途，比较读取绑定，最终本地签名并原子写最小审计；不把历史修订或回收站内容视为公开对象。
4. `content/workspace/mapper/ContentMediaPreviewMapper.java`：共享锁读取本人内容、所属修订及引用用途，插入只含主体/目标/requestId/时间的审计记录。与`media/mapper/MediaAssetMapper.findOwnedForShare`组合校验素材。
5. `media/storage/MediaObjectReader.java`：验证独立ready key、存储指纹和实际元数据，在事务外HEAD核对ETag/大小；`MediaReadSigner.java`离线签发GET及no-store/MIME响应覆盖参数。
6. `media/dto/MediaAccess.java`只有契约四个字段，toString隐藏URL；`StorageProperties.identity`保持旧上传指纹算法，UploadSigner继续复用同一结果。
7. `content/workspace/config/ContentMediaPreviewProperties.java`及Configuration定义app.content.media-preview，默认TTL300秒、来源120/分钟、用户60/分钟；RateLimiter使用独立content-media-preview作用域。
8. `V11__media_preview_binding_and_audit.sql`增加素材成组的结果key/ETag/存储指纹及content_media_preview_audit；不回填READY、不改变桶版本控制，不改写已执行迁移。
9. `ContentMediaPreviewFlowIT`覆盖真实HTTP/MySQL/Redis/MinIO、双重归属和引用、历史/回收站、权限重检、旧PUT隔离、读取绑定和审计失败；Endpoint/config/ratelimit及`media/storage/MediaReadSignerTest/IT`覆盖路径、额度、整秒过期、Range及篡改拒绝。

普通查询不调用此签名流程；处理worker仍未实现。后续worker须把校验结果写入全新ready key，设置实际元数据及绑定，再原子推进READY。已发签名仅由到期窗口约束，后续申请会重新校验权限和引用。

## 共用读取、校验与配额

`ContentReadMapper` 和 `ContentLockMapper` 位于内容模块公共层；草稿保存保留原有加锁 SQL 和顺序。
`CreateContentRequest.toDraftData()` 在创作边界构造内部草稿模型，公共校验器接收 `ContentDraftData`。
本人查询配置与限流器分别位于 `content.config`、`content.ratelimit`，与审核反馈共享同一 `app.content.query` 配额。
