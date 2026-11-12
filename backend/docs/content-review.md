# 内容审核

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### 提交当前草稿审核

```http
POST /api/v1/creator/contents/{contentId}/reviews
Authorization: Bearer <用户凭证>
Idempotency-Key: <16～128字符，网络重试复用>
Content-Type: application/json

{"expectedVersion":1,"revisionId":"当前草稿ID"}
```

成功201返回ReviewResult（review和contentVersion），Location为/api/v1/creator/contents/{contentId}/reviews/{reviewId}，响应no-store。
提交接口持久化审核记录和待发送事件；本人审核查询、审批及撤销已实现，Location可查询当前进度与反馈。MQ投递与消费后续实现。

调用链：严格参数校验→短事务复核账号/USER角色并领取幂等租约→独立Redis限流→最终事务重新授权并锁定本人内容、修订、素材和标签→冻结修订、创建审核、更新聚合、持久化事件和成功快照。
邮箱已验证且账号ACTIVE方可提交；WRITE_BLOCKED和DISABLED拒绝。管理员、审核员也不能提交他人的内容。
expectedVersion来自最新工作区，校验归属后才返回版本冲突及currentVersion；最大int32版本不能继续递增。ACTIVE内容的当前DRAFT才可提交，删除内容、终结修订、其他工作稿及已有待审核申请按契约拒绝。

标题必须非空白；ARTICLE正文必须非空白且不能关联视频，VIDEO必须关联视频；封面可省略，标签可为空。复用CommonMark校验正文图片与imageAssetIds完全对应。
素材归本人、用途匹配且全部READY；WAITING_UPLOAD/PROCESSING/FAILED返回409 MEDIA_NOT_READY。标签必须存在且ACTIVE，已停用返回422 VALIDATION_ERROR；引用在事务内按ID排序加锁，直到提交结束。
提交不检查或处理存储文件、不推进素材READY。媒体校验worker尚未实现，因此真实上传完成后仍需等待后续处理能力，不能直接将PROCESSING用于提交。

同一事务令修订DRAFT→IN_REVIEW、创建version=1的PENDING审核、设置activeReviewId并仅递增一次内容version，同时保存CONTENT_REVIEW_SUBMITTED待发送事件和幂等成功快照；任一写入失败全部回滚。
正文、素材/标签引用、工作指针及既有公开快照不变；保存接口会拒绝修改IN_REVIEW修订。purpose由服务端确定：从未发布为INITIAL，有历史发布稿为UPDATE，TAKEN_DOWN为REMEDIATION。提交整改不解除下架，只有后续审核通过才能解除。

V12新增content_review、content_review_outbox、content_review_submission_request。唯一约束保证每内容最多一个PENDING、每修订一个审核、每审核一个提交事件；活动审核指针必须关联同一内容。
事件只含稳定ID、版本、用途和时间，不保存正文、签名地址或凭据。待发送事件保持PENDING，当前没有投递器、消费者或事件管理接口。

幂等作用域为当前用户＋submitContentReview＋key，保留24小时；指纹包含contentId、expectedVersion及revisionId。相同键同请求重放首次201、审核ID和Location，即使审核状态后来变化也不重建审核；变更任何业务参数返回409 IDEMPOTENCY_KEY_REUSED。
正在处理返回409 REQUEST_IN_PROGRESS及Retry-After；预留租约60秒，超时可接管，最终写入和失败清理均校验租约令牌。重放仍检查当前账号资格和本人归属，不能绕过撤权。
app.content.review-submission独立来源60次/分钟、用户100次/小时，成功幂等重放免额度。非法请求格式、路径和未知查询参数不计数；新请求通过参数检查并取得租约后计数，包含后续404、业务409和草稿内容校验422。Redis位于数据库事务外，依赖故障返回503并释放预留。
CONTENT_REVIEW_SUBMISSION_IP_PER_MINUTE_LIMIT、CONTENT_REVIEW_SUBMISSION_USER_HOURLY_LIMIT、CONTENT_REVIEW_SUBMISSION_REDIS_KEY_PREFIX可覆盖默认配置。

2026-11-10验证：按TDD完成红、绿及重构后，`mvn clean verify`通过1257项测试
（565项单元/Web、692项集成），0失败、0错误、0跳过，打包成功。新增93项提交审核、参数、配置、Redis和并发/回滚专项检查，补充全局成功响应及第26个接口的固定错误契约检查；原始OpenAPI未修改。
真实HTTP/MySQL/Redis验证权限重检、跨作者拒绝、稿件冻结、引用状态、首发/更新/整改用途、同键及不同键并发、保存竞争、五阶段回滚、幂等快照、租约接管及旧令牌拒绝；完整回归同时覆盖现有MinIO流程。V12已执行，本次没有启动独立开发后端。
完整验证日志为`.local-dev/review-submission-full-check.log`。

### 查看本人审核进度与反馈

```http
GET /api/v1/creator/contents/{contentId}/reviews/{reviewId}
Authorization: Bearer <用户凭证>
```

成功200返回ReviewResult，复用提交审核的响应模型：review为指定审核记录的最新id、contentId、revisionId、purpose、state、version、submittedAt、decidedAt、reviewerId、reason；contentVersion为该内容当前聚合版本。
待审核时decidedAt、reviewerId、reason如实为null，有反馈后返回数据库实际值及UTC时间；审核版本与内容版本是两个独立版本，不能相互替代，也不读取首次提交的幂等快照。
响应使用统一ApiResponse包装及no-store；无需请求体、幂等键或版本参数。提交接口返回的Location现可直接访问。

调用链：拒绝未知查询参数→校验两个路径ID→共享内容查询配额→短只读事务复核当前账号与USER角色→按本人内容、指定contentId和reviewId进行单条关联查询→返回当前审核及内容版本。
只读事务采用REPEATABLE_READ，账号和角色共享锁保护authVersion、账号状态、邮箱验证与当前角色；业务关联查询使用同一MVCC快照，避免并发审核决定导致审核状态和内容版本拼接。
WRITE_BLOCKED允许读取；DISABLED、未验证邮箱或缺少USER角色拒绝。管理员和审核员也不能扩大本人范围。
内容不存在、审核不存在、审核不属于路径内容或内容不属于当前用户，统一404，不返回私有审核、反馈和版本信息。

支持PENDING、APPROVED、REJECTED、CANCELLED、INVALIDATED全部状态及INITIAL、UPDATE、REMEDIATION用途；可读取本人回收站、已撤回和下架内容的审核。
按指定审核ID查询，不要求它仍是activeReviewId；已终结历史审核在出现新工作稿或新待审记录后仍可读取。查询不要求修订继续是IN_REVIEW，不重新验证素材READY或标签ACTIVE，不改变任何审核、修订、内容版本、时间、事件或幂等快照。
本接口不提供本人审核集合/历史列表；审批、撤销另有真实事务用例，公开查询及事件投递消费仍待后续实现。这组只读测试构造终结状态，实际写入验证见下文。

与本人内容工作区和创作/回收站列表共享app.content.query及{content-query}作用域，默认来源120次/分钟、用户60次/分钟；重复读取和合法ID的404请求均计数，三类读取的次数合并计算。
路径ID不合法返回422，未知或重复查询参数返回400，均在配额与业务读取前拒绝；429带正整数Retry-After。Redis额度无法确认或数据库故障返回503，不泄露内部连接和SQL详情。
来源沿用Tomcat可信代理解析后的request.getRemoteAddr；复用现有ContentQueryProperties/RateLimiter，不新增配置、依赖或迁移，已执行的V1～V12保持不变。

2026-11-10验证：按TDD完成红、绿及重构后，`mvn clean verify`通过1306项测试
（578项单元/Web、728项集成），0失败、0错误、0跳过，打包成功。新增48项审核查询专项检查，并补充第27个接口的全局成功响应及固定错误契约检查；原始OpenAPI未修改。
真实HTTP/MySQL/Redis验证提交Location可访问、全部审核状态及用途、最新反馈和双版本、旧审核与新待审并存、回收站、跨作者/角色拒绝、事务内资格重检、合并计数、只读性、依赖失败及并发快照；完整回归同时覆盖现有MinIO流程。本次没有启动独立开发后端。
完整验证日志为`.local-dev/review-query-full-check.log`。

### 查询审核待办和历史

```http
GET /api/v1/admin/reviews?page=1&pageSize=20&state=PENDING&purpose=INITIAL
Authorization: Bearer <审核员或管理员凭证>
```

成功200返回ReviewPage：items、page、pageSize、total；items复用Review，包含审核ID、内容ID、绑定修订ID、purpose、state、审核版本、提交/处理时间、审核人员ID与反馈。可空反馈字段如实为null，时间为UTC。
契约列表不包含标题、作者昵称、正文或内容聚合版本；每条审核始终关联其原revisionId，不使用当前workingRevision或publishedRevision替代。后台详情可读取冻结修订与当前工作区，审核决定见下文。

page默认1且必须为正整数，不增加契约外上限；pageSize默认20、范围1～100。state默认PENDING，可选APPROVED/REJECTED/CANCELLED/INVALIDATED；purpose可省略，或选INITIAL/UPDATE/REMEDIATION。state与purpose按AND筛选，契约没有state=ALL或客户端排序参数。
待办固定按submittedAt升序、ID升序，优先处理较早提交；全部历史状态按submittedAt倒序、ID倒序，不改用decidedAt排序。无匹配或超末页返回200、空items及真实total；BigInteger计算偏移，超末页在转换long和执行页面SQL前返回。

调用链：Bearer当前会话认证→精确后台路径角色授权→查询参数校验→独立Redis计数→只读事务再次复核账号、authVersion、邮箱与当前角色→COUNT及当前页读取。
当前MODERATOR或ADMIN具备review:read即可读取，不额外要求USER角色；WRITE_BLOCKED可读，DISABLED及未验证邮箱拒绝。普通用户在业务参数解析和计数前返回403。
后台列表读取所有作者的审核，包含审核人员本人作品、回收站/下架内容和账号已禁用作者的历史；“不能审核自己作品”属于后续作出决定时的限制，不能据此隐藏待办或历史。
账号和角色使用共享锁防止授权在读取期间被撤回；COUNT和页面位于同一readOnly/REPEATABLE_READ快照，避免并发提交或状态变化导致总数和页面成员不一致。每页最多两条业务查询，无逐条工作区/作者/素材查询，不修改审核、修订、内容、版本、事件或幂等记录。

只允许四个已定义查询参数，各参数最多一次；未知/重复参数、分页类型或枚举错误返回400，整数分页范围错误返回422，均在额度与业务读取前拒绝。
app.content.admin-review-query独立来源120次/分钟、审核人员60次/分钟，可配置；重复读取与空页均计数，与本人内容查询和标签查询隔离。429提供正整数Retry-After；Redis无法确认额度或MySQL故障返回503，不泄露内部连接或SQL信息。
CONTENT_ADMIN_REVIEW_QUERY_IP_PER_MINUTE_LIMIT、CONTENT_ADMIN_REVIEW_QUERY_USER_PER_MINUTE_LIMIT、CONTENT_ADMIN_REVIEW_QUERY_REDIS_KEY_PREFIX可覆盖默认值，Redis作用域为{content-admin-review-query}。
审核列表、详情、媒体预览登记精确GET路径，决定登记精确POST路径，未登记路由继续默认拒绝。配置、限流、业务代码与测试均归content，auth仅提供安全入口。
复用V12及其审核队列索引，不新增迁移、依赖或业务表；原始OpenAPI保持不变。

2026-11-10验证：按TDD完成红、绿及重构后，`mvn clean verify`通过1381项测试
（620项单元/Web、761项集成），0失败、0错误、0跳过，打包成功。新增71项审核列表、参数、配置和限流专项检查，并补充后台精确路径权限及第28个接口的全局契约检查。
真实HTTP/MySQL/Redis验证默认待办、状态与用途交集、待办/历史时间和ID排序、分页及超大页码、跨作者读取、角色/邮箱/authVersion重检、WRITE_BLOCKED、独立来源/人员额度、只读性、依赖故障及COUNT/页面并发快照；完整回归同时覆盖既有MinIO流程。本次没有启动独立开发后端。
完整验证日志为`.local-dev/admin-review-list-full-check.log`。

### 读取审核快照与当前版本

```http
GET /api/v1/admin/reviews/{reviewId}
Authorization: Bearer <审核员或管理员凭证>
```

成功200返回AdminReviewDetail，只有review、content、revision三部分。review为这次审核的当前记录及反馈；revision为review.revisionId绑定的冻结快照；content为该内容当前完整工作区，包括工作稿、已发布稿、指针及当前内容版本。
review.version和content.version独立返回，不读取首次提交的幂等结果，也不把其中一个版本当作另一个。历史审核存在新工作稿或新公开稿时，revision仍保留原审核的标题、正文、封面/图片/视频/标签引用和修订ID；三个修订各自保留引用顺序。
无需请求体、幂等键或版本参数，所有未知/重复查询参数（包括列表的page/state/purpose）均400；reviewId格式错误422，均不消耗额度。响应使用统一ApiResponse包装及no-store。

调用链：Bearer会话认证→后台精确路径角色授权→参数检查→共享后台查询额度→事务内重新复核当前账号/邮箱/authVersion/角色→读取指定审核→读取关联内容→按审核绑定读取冻结修订→组装当前工作/发布修订。
MODERATOR或ADMIN具备review:read即可查询，不额外要求USER角色；WRITE_BLOCKED可读，DISABLED或资格已撤销拒绝。普通用户不能通过本接口读取任何详情，即便审核属于本人；审核人员可以查看本人作品，后续作出审核决定时再执行不能审核自己的限制。
支持PENDING、APPROVED、REJECTED、CANCELLED、INVALIDATED及本人以外作者的审核；关联内容在回收站、下架或作者账号已禁用，仍可由有权限的审核人员读取。

审核记录、冻结修订、内容根、当前工作稿/发布稿和引用位于同一readOnly/REPEATABLE_READ快照。账号及角色共享锁保护当前资格，业务读取使用MVCC，避免并发审核或编辑导致状态、快照、指针和版本混合。
不存在的reviewId返回404；已存在审核的关联内容或修订缺失/归属错误、绑定未冻结DRAFT、当前工作/发布指针状态不合法或多个封面/视频等数据损坏返回503，不伪造空快照、不回退读取新工作稿。
详情只返回媒体引用ID，不签发URL、不访问MinIO、不要求历史引用仍READY或标签仍ACTIVE；需要预览时使用下述后台媒体接口。详情查询不修改内容、审核、修订、引用、版本、时间、事件或幂等记录。

详情与审核列表共享app.content.admin-review-query及{content-admin-review-query}作用域，默认来源120次/分钟、审核人员60次/分钟，重复读取与合法ID的404请求均计数，与本人查询和标签查询隔离。429带正整数Retry-After，数据库或Redis故障返回503并隐藏内部细节。
复用AdminReviewQueryController/Service/Impl/事务和Mapper，仅新增AdminReviewDetail及ContentWorkspaceReader。Reader由原本人工作区投影逻辑提取，继续保护分页批量读取和一致性检查；本人事务仍校验本人范围，后台事务先校验审核权限，不通过模拟作者身份复用接口。
详情查询不新增配置、依赖或迁移，保留已执行V1～V12，原始OpenAPI未修改。审批和撤销见下文，事件投递消费仍待后续实现。

2026-11-10验证：按TDD完成红、绿及重构后，`mvn clean verify`通过1429项测试
（633项单元/Web、796项集成），0失败、0错误、0跳过，打包成功。新增45项审核详情专项检查，并补充精确后台详情权限及第29个接口的全局成功响应/错误契约检查。
真实HTTP/MySQL/Redis验证冻结快照与当前工作/发布修订分离、各自引用和顺序、所有审核状态、历史和回收站、角色/账号重检、列表/详情共享计数、数据损坏拒绝、依赖故障、只读性和并发一致性；完整回归同时覆盖既有MinIO流程，保护提取后的共享工作区投影。本次没有启动独立开发后端。
完整验证日志为`.local-dev/admin-review-detail-full-check.log`。

### 预览审核修订关联媒体

```http
GET /api/v1/admin/reviews/{reviewId}/media/{assetId}/access
Authorization: Bearer <审核员或管理员凭证>
```

成功200返回MediaAccess：assetId、url、expiresAt、contentType。签名默认300秒、可配置；URL只绑定GET、实际MIME及inline/no-store响应覆盖参数，支持Range，对象请求不携带业务Bearer。业务响应与对象响应均no-store，expiresAt使用签名编码的UTC整秒。
无需请求体、幂等键或版本参数；reviewId/assetId格式错误422，未知/重复查询参数400，在计数和业务查询前拒绝，客户端不能指定对象路径、修订或有效期。

MODERATOR或ADMIN具备review:read即可申请，不要求额外USER角色；WRITE_BLOCKED可读，DISABLED、未验证邮箱、authVersion或角色失效均拒绝。普通用户即使是作者也不能通过后台接口取得预览地址；审核人员可查看本人作品，不能审核自己属于后续决定接口的限制。
素材必须被review.revisionId实际引用，并属于该内容作者，不能用审核人员自己的素材替换。封面/正文图片要求CONTENT_IMAGE；视频要求VIDEO内容及CONTENT_VIDEO。未知审核、未引用、其他作者素材或用途不匹配统一404，先判断这些条件再检查READY，避免泄露无关素材状态。
支持所有审核状态及回收站/下架内容、已禁用作者的历史审核，但绑定修订必须是IN_REVIEW或终结快照；不从当前工作稿或公开稿补造引用，不为DRAFT伪造审核预览。

调用链：参数检查→独立Redis计数→prepare短只读事务复核资格和目标→事务外MinIO HEAD→finish事务重新复核→本地GET签名和敏感审计提交→返回结果。
事务内按账号/角色、内容、审核、修订/引用、素材共享锁读取；预读contentId后再次以审核ID和contentId绑定核对。HEAD连接3秒、读写5秒、调用总超时10秒，不在数据库事务中等待存储网络。
最终比较review/content/revision/author和完整结果对象绑定。HEAD期间发生撤权、移除引用、换作者、素材失败或绑定变化时不发放旧地址；更换审核修订即使仍引用同一素材也不能沿用旧核验结果。普通工作区编辑、审核状态终结不自动禁止仍有权限读取的历史快照。
WAITING_UPLOAD/PROCESSING/FAILED返回409 MEDIA_NOT_READY；READY缺实际元数据或绑定、结果对象缺失、ETag/大小/存储指纹不符、绑定源source路径等返回503，不回退读取原始上传对象。
继续使用独立ready结果key，不修改桶版本控制、不签发ready路径PUT。本人和后台预览共用ObjectReference.fromAsset校验；存储与签名错误不输出内部路径、凭据或URL。

V13新增content_review_media_preview_audit，记录actorId、reviewId、contentId、revisionId、assetId、动作ADMIN_REVIEW_MEDIA_PREVIEW、requestId、签发/到期时间。没有完整URL、对象路径、正文或凭据。每次成功授权独立记审计；审计写入失败或事务提交失败返回503，不把已构造但未完成审计的签名交给客户端。当前没有该审计表的公开查询/修改接口。
app.content.admin-review-media-preview独立来源120次/分钟、审核人员60次/分钟；重复成功、404和未就绪请求都计数，与审核列表/详情、本人媒体预览和普通内容查询隔离。429带正整数Retry-After。
CONTENT_ADMIN_REVIEW_MEDIA_PREVIEW_READ_URL_TTL_SECONDS、CONTENT_ADMIN_REVIEW_MEDIA_PREVIEW_IP_PER_MINUTE_LIMIT、CONTENT_ADMIN_REVIEW_MEDIA_PREVIEW_USER_PER_MINUTE_LIMIT、CONTENT_ADMIN_REVIEW_MEDIA_PREVIEW_REDIS_KEY_PREFIX可覆盖默认值；TTL限制1～604800秒，Redis作用域为{content-admin-review-media-preview}。
已签发的URL在到期前可能继续有效，后续申请重新验证权限和引用；该接口不实现即时撤销已发URL。V1～V12保持不变，原始OpenAPI未修改；媒体校验worker、READY推进、视频/分片上传和MQ消费仍待后续实现，审批见下文。测试构造READY对象和MP4读取样本，不代表后台处理或视频上传已完成。

2026-11-10验证：按TDD完成红、绿及重构后，`mvn clean verify`通过1502项测试
（659项单元/Web、843项集成），0失败、0错误、0跳过，打包成功。新增70项后台预览、参数、配置及限流专项检查，并补充精确后台预览权限和第30个接口的全局契约检查。
真实HTTP/MySQL/Redis/MinIO验证GET/Range、方法和无签名拒绝、审核冻结引用、作者/用途约束、历史/回收站、READY结果绑定、HEAD事务边界、最终权限与目标重检、敏感审计、写审计失败及独立额度；既有本人预览52项流程检查继续通过。V13已执行，本次没有启动独立开发后端。
完整验证日志为`.local-dev/admin-review-media-preview-full-check.log`。

### 批准或驳回指定修订

```http
POST /api/v1/admin/reviews/{reviewId}/decisions
Authorization: Bearer <审核员或管理员凭证>
Idempotency-Key: <16～128字符，网络重试复用>
Content-Type: application/json

{"expectedContentVersion":2,"expectedReviewVersion":1,"decision":"APPROVE","reason":"已核对冻结修订，审核通过。"}
```

成功200返回ReviewResult：终结后的review和contentVersion；reason对APPROVE/REJECT都必填，1～1000个Unicode码点且不能全为空白，保留原文本。版本取自最近一次后台详情的content.version和review.version，不使用revisionNumber替代。两个版本均严格接受1～2147483647整数：类型错误400、整数越界422；未知字段/查询参数及重复幂等请求头400，非法路径/键长度422。

调用链：Bearer当前会话认证→review:decide角色授权→参数校验→短事务领取幂等租约→独立Redis配额→最终事务重新授权并锁定内容/审核/修订/素材→状态和指针更新→审计、Outbox、幂等成功快照→提交后返回。
只有当前邮箱已验证、账号ACTIVE的MODERATOR或ADMIN可决定，不额外要求USER角色；普通用户拒绝，WRITE_BLOCKED也不能写入。即使ADMIN也不能审核自己的作品，自审在版本检查前返回403 PERMISSION_DENIED。
内容必须ACTIVE，审核必须PENDING并等于activeReviewId，绑定修订必须等于workingRevisionId且仍IN_REVIEW。双版本或状态不匹配返回契约409；内容版本冲突优先于审核版本冲突，currentVersion对应错误码的那种版本。版本已到int32上限不允许继续递增。终结审核用新键重复决定不会伪装成功。

最终事务按审核人员账号→幂等记录→内容→审核→修订→排序后的素材加锁，并用WHERE旧版本/旧状态进行条件更新。READ_COMMITTED避免预读ID形成旧快照，写入判断依靠持有的锁和条件更新；两个审核人员同时用相同旧版本操作时，只能一个提交，另一个得到409。
APPROVE重新检查冻结修订引用素材的作者归属、用途和READY状态，未就绪返回409 MEDIA_NOT_READY；驳回不要求素材READY。提交后停用的标签不阻止批准，保留已提交tagIds；新提交审核仍要求标签ACTIVE。审批不在事务内访问MinIO或重新扫描文件，真实文件校验及READY推进仍由后续处理worker承担。

批准将修订和审核设APPROVED，设置reviewerId/decidedAt/reason，分别递增review.version和content.version，公开指针切换到审核绑定修订并设PUBLISHED，清空workingRevisionId和activeReviewId。原公开修订正文、ID、引用及历史保留。
驳回将修订和审核设REJECTED并保存反馈，同样增加两种版本、清空工作/活动审核指针，保留原publishedRevisionId和publicationStatus：普通更新的旧稿继续公开，WITHDRAWN不自动公开，TAKEN_DOWN保持下架。终结修订不能原地编辑，后续修改须新建修订。
只有TAKEN_DOWN且purpose=REMEDIATION的新整改稿批准后才能解除下架；普通UPDATE不能借审批解除。INITIAL/UPDATE/REMEDIATION的用途与历史发布指针、当前发布状态不一致时拒绝批准。
V14增加first_published_at和published_at；批准首次填充first_published_at，后续使用COALESCE保留首次时间，每次批准更新published_at，避免更新内容重置首次发布时间。驳回不改变两项发布时间。此前记录不回填估计时间，字段供后续公开查询使用，当前ContentWorkspace响应不增加契约外字段。

content_review_decision_audit保存决定主体/目标、原因、前后两种版本、requestId及时间。CONTENT_REVIEW_DECIDED事件覆盖两种决定，批准再写CONTENT_PUBLISHED；复用content_review_outbox，事件带author/reviewer/review/revision/content标识及内容版本，不带未审核正文或存储凭据。
审核、修订、内容指针/时间、审计、待发送事件和成功幂等快照同一数据库事务提交，任一写入失败全部回滚并返回503。当前事件保持PENDING，尚无MQ投递器、通知/搜索/信息流消费者；未来消费者必须按事件ID去重并检查内容版本，不能用迟到的旧发布事件解除后续下架。

幂等作用域为当前审核人员＋decideReview＋key，有效24小时；指纹含reviewId、双版本、decision及reason。相同键相同参数返回首次200业务快照，免配额、不重复审计或发事件，内容后来下架也不会触发重新发布。相同键不同参数409 IDEMPOTENCY_KEY_REUSED；正在执行409 REQUEST_IN_PROGRESS并给Retry-After。重放仍检查当前身份、账号状态、角色和自审限制。
预留租约60秒，崩溃后可接管，最终写入及清理校验租约令牌，旧执行者不能覆盖或删除新租约。Redis计数在数据库事务外；失败释放仍处理中的预留，成功事实可在响应丢失后找回。
app.content.review-decision独立来源60次/分钟、审核人员100次/小时，成功重放免配额；请求格式校验失败不计数，取得预留后的合法404、业务冲突等计数。CONTENT_REVIEW_DECISION_IP_PER_MINUTE_LIMIT、CONTENT_REVIEW_DECISION_USER_HOURLY_LIMIT、CONTENT_REVIEW_DECISION_REDIS_KEY_PREFIX可覆盖默认值。
V14扩展Outbox事件约束、增加发布时刻和审批审计/幂等表，V1～V13原样保留，原始OpenAPI未修改。当前完成数据库审批和发布指针切换，作者撤销见下文；公开内容查询、删除恢复、MQ及通知消费等仍待后续实现。

2026-11-10验证：按TDD完成红、绿及重构后，`mvn clean verify`通过1628项测试
（724项单元/Web、904项集成），0失败、0错误、0跳过，打包成功。新增123项审核决定、参数、配置、限流专项检查，并补充精确后台决定权限及第31个接口的全局契约检查。
真实HTTP/MySQL/Redis验证批准/驳回、双版本和溢出、禁止本人审批、跨审核人员单一成功、旧公开稿及首次时间保留、整改解除下架、素材/标签策略、七阶段回滚、幂等重放/冲突/配额、租约接管和旧令牌拒绝；完整回归同时覆盖既有MinIO及共享素材校验。V14已执行，本次没有启动独立开发后端。
完整验证日志为`.local-dev/review-decision-full-check.log`。

### 撤销本人待审核申请

```http
PUT /api/v1/creator/contents/{contentId}/reviews/{reviewId}/cancellation
Authorization: Bearer <本人凭证>
Content-Type: application/json

{"expectedVersion":2,"expectedReviewVersion":1}
```

成功200返回ReviewResult，包含审核当前状态和contentVersion；无需Idempotency-Key。expectedVersion取内容聚合版本，expectedReviewVersion取审核版本；只接受1～2147483647整数。格式类型错误400、整数范围错误422；路径格式错误422，未知/重复查询参数及未知JSON字段400，均在额度与业务读取前拒绝。
当前邮箱已验证、账号ACTIVE且保有USER角色的内容作者才能撤销。事务重新核对authVersion、账号和当前角色；WRITE_BLOCKED、DISABLED不可写。管理员/审核员也不能越权撤销其他作者的审核；内容不存在、不归本人、审核不存在或与contentId不匹配统一404，不暴露版本。

调用链：严格解析请求→路径检查→独立Redis计数→事务锁定账号、本人内容、该内容审核、绑定修订→首次撤销或确认本人重复撤销→提交后返回。锁顺序与审批相同，写事务使用READ_COMMITTED和行锁/条件更新保护判断。
首次撤销要求双版本匹配、内容ACTIVE、审核仍PENDING且等于activeReviewId、修订等于workingRevisionId并仍IN_REVIEW。内容版本冲突优先于审核版本冲突，409的currentVersion对应错误码表示的版本；版本已到int32上限不能再做首次状态变更。不在当前待办/绑定状态的请求409 REVIEW_NOT_PENDING。
原子更新审核和修订为CANCELLED，审核/内容版本各增加一次，记录decidedAt，reviewerId/reason为null；清空当前工作和活动审核指针。冻结标题、正文、ID和素材/标签引用保留，不改回DRAFT，不修改素材或标签状态，未就绪素材和停用标签不妨碍撤销。
原publishedRevisionId、publicationStatus、first_published_at和published_at全部保留：旧公开稿继续公开，WITHDRAWN和TAKEN_DOWN保持原样。撤销不解除下架、不删除媒体对象；继续编辑须新建修订，后续草稿接口仍待实现。

V15新增content_review_cancellation_audit，记录本人撤销事实：actor/content/review/revision、前后两种版本、requestId和时间，每审核一条。Outbox增加CONTENT_REVIEW_CANCELLED，携带内容/审核版本及原公开状态；记录和事件与业务状态在同一事务提交，任一步失败503并整体回滚。
仅当审核为CANCELLED且存在匹配本人、内容和修订的撤销记录，才允许重复成功；不能把APPROVED、REJECTED、INVALIDATED或无本人撤销依据的CANCELLED当作成功。合法原版本重试无需匹配撤销后版本，仍先核对当前身份及归属，返回审核和内容当前版本，不缓存首次结果。
重复撤销不再增加版本、不改时间、不重复记记录/事件；即使内容已创建后续工作稿，也不能清除或改动新稿。两个作者并发重试都可200，但只发生一次变更；与审核员批准/驳回并发时，只有一个状态变更成功，另一个409。

app.content.review-cancellation独立来源120次/分钟、用户60次/分钟，重复成功、合法404和业务409都计数，与查询、提交、审批配额隔离。429带正整数Retry-After；Redis无法确认额度或数据库故障503，不返回内部连接、SQL细节。
CONTENT_REVIEW_CANCELLATION_IP_PER_MINUTE_LIMIT、CONTENT_REVIEW_CANCELLATION_USER_PER_MINUTE_LIMIT、CONTENT_REVIEW_CANCELLATION_REDIS_KEY_PREFIX可覆盖默认值，Redis作用域为{content-review-cancellation}。
不新增依赖或幂等请求表，V1～V14保留原样，原始OpenAPI未修改。事件暂为PENDING，MQ投递、作者新草稿、删除恢复及公开内容查询等继续由后续用例实现。

2026-11-10验证：按TDD完成红、绿及重构后，`mvn clean verify`通过1722项测试
（773项单元/Web、949项集成），0失败、0错误、0跳过，打包成功。新增93项本人撤销、参数、配置与限流专项检查，并补充第32个接口的全局契约检查。
真实HTTP/MySQL/Redis验证首次/重复撤销、双版本/溢出、撤销事实依据、新工作稿保护、跨作者/管理角色拒绝、当前资格重检、旧公开稿/时间与冻结引用保留、六阶段回滚、重复并发成功、与批准/驳回互斥、独立来源/用户配额及依赖故障；完整回归同时覆盖既有审批及MinIO流程。V15已执行，本次没有启动独立开发后端。
完整验证日志为`.local-dev/review-cancellation-full-check.log`。

## 调用链阅读顺序

### 提交当前草稿审核怎样阅读

1. `content/review/controller/ContentReviewSubmissionController.java`：POST /api/v1/creator/contents/{contentId}/reviews，严格解析SubmitReviewRequest和Idempotency-Key，201返回ReviewResult和Location。
2. `content/review/service/ContentReviewSubmissionService.java`及`service/impl/ContentReviewSubmissionServiceImpl.java`：参数检查、幂等领取、成功重放免额度、独立Redis计数、最终提交和异常释放。
3. `content/review/service/transaction/ContentReviewSubmissionTransactionService.java`：领取60秒租约；最终事务重新核对账号、角色、本人范围、聚合版本和当前DRAFT，冻结修订并原子写审核、指针、事件和24小时幂等快照。
4. `content/review/validation/ContentReviewSubmissionValidator.java`：复用CommonMark图片引用校验，要求标题/文章正文非空白或视频存在，按ID排序锁定素材与标签，确认归属、用途、READY和ACTIVE。
5. `content/review/mapper/ContentReviewSubmissionMapper.java`及RequestMapper：审核和事件写入、版本条件更新、幂等租约令牌校验；复用content.mapper.ContentLockMapper的聚合/修订锁，使用共享ContentWorkspaceReader装配已加锁内容。
6. `content/review/config/ContentReviewSubmissionProperties.java`与Configuration、`ratelimit/ContentReviewSubmissionRateLimiter.java`：app.content.review-submission，独立content-review-submission作用域，默认来源60/分钟、用户100/小时。
7. `V12__content_review_submission.sql`：content_review、content_review_outbox、content_review_submission_request，数据库保证每内容最多一个PENDING审核、每修订一条审核、每审核一条提交事件，activeReviewId必须指向同内容审核。

本人审核查询见第27节，审批发布状态见第31节；事件投递和消费仍未实现，待发送事件保持PENDING。素材处理worker仍未实现，完成上传的PROCESSING不能用于审核提交。

### 本人审核进度与反馈怎样阅读

1. `content/review/controller/ContentReviewQueryController.java`：GET /api/v1/creator/contents/{contentId}/reviews/{reviewId}，拒绝未知查询参数，返回ReviewResult，不要求幂等键。
2. `content/review/service/ContentReviewQueryService.java`及`service/impl/ContentReviewQueryServiceImpl.java`：校验两个路径ID，共享ContentQueryRateLimiter额度，调用只读事务并转换依赖错误。
3. `content/review/service/transaction/ContentReviewQueryTransactionService.java`：REPEATABLE_READ短事务、账号及角色共享锁重新授权、WRITE_BLOCKED可读，投影最新审核状态、反馈和内容聚合版本。
4. `content/review/mapper/ContentReviewQueryMapper.java`：单条JOIN限制当前作者、contentId和reviewId，读取匹配的审核及内容版本；不依赖activeReviewId，不加载素材或无关正文。
5. 复用`ReviewResult`及`ReviewView`，区分当前内容版本和审核版本，保留可空反馈及UTC时间。复用app.content.query，工作区详情、列表、审核查询合并计算额度。
6. `ContentReviewQueryEndpointTest`及`ContentReviewQueryFlowIT`覆盖路径/查询参数、提交Location、全部审核状态与反馈、历史和回收站、双ID归属、角色边界、权限重检、共享额度、只读性、并发一致性及契约。

本人审核查询不新增迁移、配置或依赖，不改写V12；审核决定见第31节、本人撤销见第32节，本人审核列表与事件消费仍待后续实现。

### 后台审核待办和历史怎样阅读

1. `content/review/controller/AdminReviewQueryController.java`：GET /api/v1/admin/reviews，仅允许单个page、pageSize、state、purpose参数；默认PENDING，返回ReviewPage。
2. `content/review/service/AdminReviewQueryService.java`与`service/impl/AdminReviewQueryServiceImpl.java`：分页和枚举校验、独立计数、只读事务与依赖错误转换；BigInteger保留无契约上限的页码。
3. `content/review/service/transaction/AdminReviewQueryTransactionService.java`：账号和角色共享锁复核当前review:read资格，COUNT与页面处于同一只读REPEATABLE_READ快照；超末页直接返回空items及真实total。
4. `content/review/mapper/AdminReviewQueryMapper.java`：COUNT和页面共用state/purpose筛选，待办submitted_at/id升序，历史submitted_at/id倒序。直接读取审核绑定revisionId，不把新工作稿替换为审核对象。
5. `content/review/dto/ReviewPage.java`复用ReviewView，仅返回items/page/pageSize/total。列表不扩展标题、作者昵称、正文或内容当前版本。
6. `content/review/config/AdminReviewQueryProperties.java`及Configuration、`ratelimit/AdminReviewQueryRateLimiter.java`：app.content.admin-review-query，独立content-admin-review-query作用域，默认来源120/分钟、审核人员60/分钟。
7. `auth/security/AdminAuthorizationManager.java`登记审核集合、详情、媒体预览的精确GET路径及决定的精确POST路径，允许MODERATOR/ADMIN；业务读取和角色复核留在content，未登记路径继续默认拒绝。
8. `AdminReviewListEndpointTest`、`AdminReviewListFlowIT`及配置/限流测试覆盖筛选、排序、分页、角色/账号重检、并发快照、独立额度、依赖故障、只读性和契约。

列表复用V12和既有审核模型，不新增迁移或依赖；后台审核详情见第29节，媒体预览见第30节，审批见第31节，事件投递消费后续实现。

### 审核冻结快照和内容当前版本怎样阅读

1. `content/review/controller/AdminReviewQueryController.java`新增GET /api/v1/admin/reviews/{reviewId}，详情不接受列表参数或其他查询参数，返回AdminReviewDetail。
2. `content/review/service/AdminReviewQueryService.java`及其Impl新增get：校验reviewId，共享后台审核查询额度，进入详情事务并转换依赖故障。
3. `content/review/service/transaction/AdminReviewQueryTransactionService.java`新增read：复核当前MODERATOR/ADMIN资格，读取指定审核、关联内容、审核绑定修订及当前工作/发布修订；所有业务读取处于同一只读REPEATABLE_READ快照。
4. `content/review/mapper/AdminReviewQueryMapper.findById`按reviewId读取记录；后台事务通过`ContentWorkspaceReader.findContent`读取关联内容，底层使用共享`content/mapper/ContentReadMapper.findById`，本人查询仍使用findOwned约束归属。
5. `content/service/read/ContentWorkspaceReader.java`承接原工作区的投影、修订关联检查、引用读取和分页批量组装。本人事务与后台事务各自完成授权，再调用该组件；它不承担身份认证，不构造作者身份来绕过本人Service。
6. `ContentWorkspaceReader.frozenRevision`严格按审核中的contentId/revisionId读取IN_REVIEW或终结快照，不能回退到当前工作稿；工作指针仅允许DRAFT/IN_REVIEW，发布指针仅允许APPROVED，关联损坏或重复标量引用返回503。
7. `content/review/dto/AdminReviewDetail.java`仅组合review/content/revision，复用ReviewView、ContentWorkspace、RevisionView；审核版本位于review.version，内容当前版本位于content.version，媒体只返回引用ID。
8. `AdminReviewDetailEndpointTest`及`AdminReviewDetailFlowIT`验证参数、角色、历史/回收站、三份修订和各自引用、共享额度、资格重检、关联损坏、只读性、并发快照及契约；工作区和列表测试继续保护共享投影。

列表与详情共享app.content.admin-review-query，不新增配置、依赖或迁移；后台媒体签名见第30节，审批见第31节。

### 审核修订关联媒体预览怎样阅读

1. `content/review/controller/AdminReviewMediaPreviewController.java`：GET /api/v1/admin/reviews/{reviewId}/media/{assetId}/access，拒绝所有查询参数，返回MediaAccess。
2. `content/review/service/AdminReviewMediaPreviewService.java`及Impl：路径校验、独立限流、prepare、事务外MinIO HEAD、finish，统一转换数据依赖故障。
3. `content/review/service/transaction/AdminReviewMediaPreviewTransactionService.java`：两次复核当前MODERATOR/ADMIN、账号与authVersion，按内容→审核→修订/引用→素材共享锁检查冻结修订、作者归属和用途；最终比较完整Proof，再签名并写审计。
4. `content/review/mapper/AdminReviewMediaPreviewMapper.java`：预读审核contentId，再锁定内容和同内容审核；读取绑定修订/用途，插入只含审核和资源ID的敏感预览审计。不会用当前工作稿替代审核绑定。
5. 复用`media/storage/MediaObjectReader`及`MediaReadSigner`，与本人预览共用ObjectReference.fromAsset结果绑定校验。HEAD核验ETag/大小在事务外，本地SigV4签名与审计在最终事务内。
6. `content/review/config/AdminReviewMediaPreviewProperties.java`及Configuration、RateLimiter：app.content.admin-review-media-preview，TTL300秒，独立来源120/分钟、审核人员60/分钟，所有调用计数，非法参数除外。
7. `V13__review_media_preview_audit.sql`新增content_review_media_preview_audit，绑定actor/review/content/revision/asset、动作、requestId和签发/到期时间；不存URL、对象路径或凭据，审计失败不返回签名。
8. `AdminReviewMediaPreviewEndpointTest/FlowIT`及配置/限流测试覆盖契约、冻结引用、作者归属、全部审核状态、READY绑定、真实GET/Range、权限及目标变化、审计失败和独立额度；既有本人预览继续验证共享对象校验。

后台预览保留V1～V12原样，新增V13。审批见第31节；媒体处理worker、视频上传和事件消费仍待后续实现。

### 批准或驳回指定修订怎样阅读

1. `content/review/controller/ReviewDecisionController.java`：POST /api/v1/admin/reviews/{reviewId}/decisions，要求单个Idempotency-Key，拒绝查询参数，200返回ReviewResult。
2. `content/review/dto/ReviewDecisionRequest.java`：两个版本用BigInteger接收并校验int32范围；只接受整数、APPROVE/REJECT、1～1000码点且非空白的reason，不接受未知字段。
3. `content/review/service/ReviewDecisionService.java`与Impl：路径/键校验，计算含审核ID、双版本、决定及原原因的指纹；领取幂等租约，成功重放免配额，其余请求独立计数后提交，异常时按令牌释放预留。
4. `content/review/service/transaction/ReviewDecisionTransactionService.java`：claim与finish各用READ_COMMITTED短事务；按账号→幂等→内容→审核→修订→素材锁定并复核当前资格、非本人、双版本、ACTIVE及当前PENDING/IN_REVIEW绑定。最终事务包含状态/指针、审计、事件和成功快照。
5. `content/review/mapper/ReviewDecisionMapper.java`：行锁、带旧版本/旧状态条件的更新、审计及Outbox写入；批准切换publishedRevisionId并设PUBLISHED，驳回保留旧公开指针及状态，两者均清空工作/活动审核指针、分别递增内容和审核版本。
6. `content/review/validation/ContentReviewMediaValidator.java`：提交与批准共用作者归属、用途和READY检查，按素材ID排序加锁；提交时另检查标签ACTIVE，批准保留已提交标签引用。驳回不经过素材就绪门槛。
7. `content/review/entity/ReviewDecisionRequestRecord.java`与RequestMapper：24小时结果快照、60秒预留租约及令牌条件写入/释放，防止旧执行者覆盖新领取结果。重放仍要求当前审核权限，不重新发布。
8. `content/review/config/ReviewDecisionProperties.java`、Configuration及RateLimiter：app.content.review-decision，默认来源60/分钟、审核人员100/小时，独立content-review-decision作用域，成功重放免额度。
9. `V14__review_decision_and_publication.sql`：首次/本次发布时间、content_review_decision_audit、content_review_decision_request，扩展原Outbox允许决定及发布事件；V1～V13保持不变。
10. `ReviewDecisionEndpointTest`、`ReviewDecisionFlowIT`及配置/限流测试：严格输入、双版本/溢出、自审拒绝、七阶段回滚、跨审核人员竞争、旧公开稿/整改、首次发布时间、素材/标签规则、幂等与租约、配额及契约。

审计和Outbox与业务同事务；CONTENT_REVIEW_DECIDED覆盖两种决定，CONTENT_PUBLISHED仅批准时写入。事件包含内容版本，作者撤销见第32节，实际MQ投递、通知/索引消费和公开查询仍待后续实现。

### 撤销本人待审核申请怎样阅读

1. `content/review/controller/ReviewCancellationController.java`：PUT /api/v1/creator/contents/{contentId}/reviews/{reviewId}/cancellation，拒绝查询参数，无幂等请求头要求，200返回ReviewResult。
2. `content/review/dto/CancelReviewRequest.java`：expectedVersion为内容聚合版本，expectedReviewVersion为审核版本；BigInteger接收整数后校验正数/int32范围，不接受额外字段。
3. `content/review/service/ReviewCancellationService.java`与Impl：路径检查、独立Redis计数、事务调用和依赖错误转换；重复成功、404、业务冲突均计数。
4. `content/review/service/transaction/ReviewCancellationTransactionService.java`：账号→本人内容→同内容审核→修订锁顺序与审批兼容；首次校验双版本和当前PENDING/IN_REVIEW绑定，原子更新、记录撤销事实和写事件。
5. `content/review/mapper/ReviewCancellationMapper.java`：本人范围及同内容关联锁、旧版本/旧状态条件更新；仅查询到同一actor/content/review/revision的撤销记录才允许CANCELLED重复成功。
6. `content/review/config/ReviewCancellationProperties.java`、Configuration及RateLimiter：app.content.review-cancellation，来源120/分钟、用户60/分钟，独立content-review-cancellation作用域。
7. `V15__review_cancellation.sql`：content_review_cancellation_audit记录撤销者、目标、前后版本、requestId和时间；复用Outbox并增加CONTENT_REVIEW_CANCELLED事件约束，保留V1～V14原样。
8. `ReviewCancellationEndpointTest`、`ReviewCancellationFlowIT`及配置/限流测试：双版本、重复/新草稿保护、快照与公开稿保留、跨用户/角色拒绝、资格重检、事务回滚、与审批并发互斥、配额及契约。

重复成功返回审核和内容当前版本，不使用首次结果缓存，不重复写记录或事件。首次撤销后修订保持CANCELLED，继续编辑需新建草稿；后续草稿、删除/恢复及MQ消费仍待各自用例实现。

## 创作与审核之间的依赖边界

- 审核详情通过共享 Reader 获取内容；提交事务通过共享行锁 Mapper 锁定内容与修订，再直接装配工作区。
- 账号、角色、双版本、活动审核和素材/标签校验仍属于原有业务事务，未迁入共享 Reader。
- 审核提交使用内部 `ContentDraftData` 复用草稿语法校验，避免构造创作 HTTP 请求 DTO。
- 本人审核反馈通过 `content.ratelimit.ContentQueryRateLimiter` 与本人工作区、列表共享原有配额。
- 本人查询 Mapper 的局部 `OwnedReviewRow` 带有 `contentVersion`；共享 `ReviewRow` 继续表示审核记录本身。
