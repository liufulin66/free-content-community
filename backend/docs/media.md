# 上传与媒体

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

文中 `src/` 路径相对 `backend/`；验证数字为对应开发阶段的历史记录，当前结果以最新执行为准。

## 接口行为与约定

### 获取当前上传限制与签名策略

`GET /api/v1/media/upload-policy`需要有效Bearer身份，无请求体，返回200及`ApiResponse<UploadPolicy>`。
接口属于`media`模块，Controller依赖UploadPolicyService接口，由service.impl中的实现读取配置并构造独立响应DTO。
策略读取不访问对象存储，不创建上传会话，不修改账号资料或延长登录会话；认证仍需查询Redis及MySQL。
WRITE_BLOCKED允许查询；缺少或失效身份返回401，DISABLED返回403，认证依赖不可用返回503。
成功及失败响应继续使用统一requestId和no-store规范，不返回桶名、内部存储地址、密钥或签名URL。

配置集中在`app.media.upload-policy`，启动时校验数值范围，并保留不可变的MIME类型列表：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| max-image-bytes | MEDIA_MAX_IMAGE_BYTES | 10485760（10 MiB） |
| max-video-bytes | MEDIA_MAX_VIDEO_BYTES | 524288000（500 MiB） |
| allowed-image-mime-types | MEDIA_ALLOWED_IMAGE_MIME_TYPES | image/jpeg,image/png,image/webp,image/gif |
| allowed-video-mime-types | MEDIA_ALLOWED_VIDEO_MIME_TYPES | video/mp4 |
| multipart-threshold-bytes | MEDIA_MULTIPART_THRESHOLD_BYTES | 16777216（16 MiB） |
| max-part-count | MEDIA_MAX_PART_COUNT | 10000 |
| upload-url-ttl-seconds | MEDIA_UPLOAD_URL_TTL_SECONDS | 600（10分钟） |

环境变量中的MIME列表以逗号分隔。配置在应用启动时绑定，修改后需重启后端。
图片和视频大小范围为1～9007199254740991字节，分片阈值及签名秒数为正整数，分片数范围为1～10000。
原先app.storage下未绑定的图片大小、类型及签名时长预留配置已迁入media；app.storage继续保存存储连接参数。

该节描述策略查询；私有上传会话创建见下节。返回视频、分片参数不代表视频或分片上传接口已经实现。
创建会话已复用这组配置；后续完成上传还须核验实际大小、类型、归属和READY状态，前端检查不能替代服务端检查。
非空头像绑定仍等待素材模块，不因本接口上线而开放。

专项检查覆盖配置覆盖、数值范围、认证状态、响应字段白名单、真实HTTP/MySQL/Redis及生成的OpenAPI必填字段和范围。

2026-11-07验证：`mvn clean "-Dtest=*Test,*Tests,*IT" package`通过全部199项测试（新增16项），
0失败、0错误、0跳过，打包成功。按TDD验证了缺失路由404、非法数值配置、列表可变性及OpenAPI约束缺失，修复后回归通过。

### 创建私有文件上传会话

`POST /api/v1/media/uploads`需要Bearer身份及16～128字符的`Idempotency-Key`，成功201，
返回`ApiResponse<UploadSession>`和`Location: /api/v1/media/uploads/{uploadId}`。
请求接收scene、fileName、contentType、整数sizeBytes及可选的64位十六进制sha256。
用户取认证上下文，来源取可信代理后的客户端地址，不接受ownerId、存储路径或存储桶等额外字段。

当前按P0只接受AVATAR、CONTENT_IMAGE、REPORT_ATTACHMENT用途的策略白名单图片，默认最大10 MiB。
CONTENT_VIDEO、非白名单MIME及需要分片的申请返回415，图片超限返回413。
MIME去首尾空白并转小写，sha256转小写，文件名仅保存显示值；数值字符串、小数及错误字段类型拒绝，避免隐式转换。
缺失必填请求头返回400 MALFORMED_REQUEST，幂等键长度错误及字段约束错误返回422。

业务配置为`app.media.upload-creation`：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| session-ttl-seconds | MEDIA_UPLOAD_SESSION_TTL_SECONDS | 7200（2小时） |
| ip-per-minute-limit | MEDIA_UPLOAD_IP_PER_MINUTE_LIMIT | 60 |
| user-hourly-limit | MEDIA_UPLOAD_USER_HOURLY_LIMIT | 100 |
| redis-key-prefix | MEDIA_UPLOAD_REDIS_KEY_PREFIX | fc:media: |

上传签名沿用app.media.upload-policy.upload-url-ttl-seconds，默认600秒，且不会超过会话有效期。
Redis Lua原子检查用户小时窗口及TCP来源分钟窗口；拒绝时两个计数都不增加，429返回Retry-After。
窗口从各计数首次获准申请时起算。
认领后确认是新请求才计数；重放和同键不同请求直接返回，不消耗额度。新请求的策略拒绝及后续数据库失败不自动退还额度。
仅在TCP对端属于可信代理网段时采用X-Forwarded-For；可信网段由TRUSTED_PROXY_PATTERN配置。

#### 持久化、并发及故障恢复

V5迁移新增media_asset、media_upload_session和media_upload_request，保留V1～V4及既有账号数据。
素材保存声明大小、声明类型及可选摘要，初始WAITING_UPLOAD，实际校验字段尚为空；上传会话初始UPLOADING、version=1。
素材ID、上传ID及private/{userId}/{assetId}/source对象键均由服务端分配，不使用原始文件名作为路径。

处理顺序：检查请求及限流 → 认领幂等记录并提交短事务 → 检查当前文件策略并准备签名 → 最终写事务 → 返回201。
两个事务都锁定并复核账号authVersion、ACTIVE及邮箱验证状态，按账号行再幂等记录的顺序加锁。
最终事务把素材、上传会话和幂等成功结果一起提交；任一数据库写入失败整体回滚。
数据库和Redis不构成分布式事务，签名准备不占用数据库写事务。

幂等作用域为用户＋createUpload＋键，24小时有效，数据库保存键摘要和规范化请求指纹。
同键同请求重放原资源、状态、Location及签名快照，且不消耗限流额度；同键不同请求返回409 IDEMPOTENCY_KEY_REUSED。
处理中返回409 REQUEST_IN_PROGRESS和Retry-After。处理租约60秒，过期后重试可接管，最终提交复核租约令牌，防止旧请求覆盖新结果。
准备失败只释放本次持有的PROCESSING记录；数据库故障无法释放时，后续重试依靠租约恢复。
24小时过期后允许重新使用该键创建新资源，原资源保留。过期幂等记录按请求惰性回收，全量清理随后续后台清理流程完善。

#### 签名与实际文件上传

UploadSigner使用既有MinIO SDK的SigV4实现，固定创建时的签名时间、有效期、对象、Content-Type和Content-Length；请求提供sha256时还绑定x-amz-checksum-sha256，支持重建相同结果。
Maven显式补齐与MinIO 9.0.4匹配的okhttp-jvm 5.5.1；多平台入口包本身不包含JVM HTTP实现。
存储配置继续使用app.storage及既有MINIO_*变量，新增MINIO_REGION（默认us-east-1）。
数据库只保存签名参数及配置指纹，不保存完整签名URL或存储密钥；凭证类和上传目标的toString已脱敏。
更换存储地址、桶、区域或密钥后，旧签名快照不能用新配置静默重建，重放会返回503。

生成签名是本地计算，不逐次探测MinIO在线状态；创建成功表示业务记录已经提交，实际文件传输由客户端执行。
前端按singleUploadTarget的PUT、URL和headers发送文件二进制（必须带Content-Type、Content-Length；提供sha256时还要带x-amz-checksum-sha256），不附带业务Bearer。
签名约束方法、对象、Content-Type及Content-Length，实际大小不符会在写入前被MinIO拒绝；提供sha256时校验和也会被验证。真实格式及后续READY校验仍由完成上传流程负责。
签名过期与会话过期独立，幂等重放保留原签名到期时间；会话查询接口已支持在会话有效时重新签发地址。

当前已实现创建会话、私有素材登记、查询续签及取消。完成、异步校验及READY流程仍待实现，非空头像绑定继续按原规则拒绝。

2026-11-07验证：`mvn verify`通过全部239项测试（145 单元/Web + 94 集成），
0失败、0错误、0跳过。覆盖真实HTTP/MySQL/Redis/MinIO上传、签名篡改拒绝、私有读取拒绝、
幂等重放/冲突、重放不消耗限流额度、受信代理转发头来源、Content-Length与可选SHA-256校验和、
8并发去重、处理中409、租约接管及旧请求隔离、事务回滚、认证状态复核、限流、严格类型与生成的OpenAPI。

### 查询本人上传进度并恢复上传

`GET /api/v1/media/uploads/{uploadId}`需要Bearer身份，无请求体和Idempotency-Key，成功200及`ApiResponse<UploadSession>`。
只查询本人会话，非本人或不存在统一404；非法ID返回422及path位置的uploadId校验信息。
返回数据库当前version及原id、assetId、expiresAt，不创建新资源、不递增版本、不延长会话或登录有效期。
本期只支持SINGLE，因此partSizeBytes为null、totalParts为1、uploadedParts为[]；不虚构已上传字节数或百分比。

UPLOADING且仍有效、账号ACTIVE时，沿用原storageKey、Content-Type、声明大小及可选SHA-256生成新签名。
签名有效期取当前策略、会话剩余整秒和S3最大604800秒中的最小值，剩余不足一个签名秒时不再签发。
签名参数通过内存副本计算，不覆盖创建时的signature_at、signature_ttl_seconds及signer_id，创建接口仍能重放原结果。
SINGLE中断后使用同一个目标重新传完整文件；浏览器丢失File对象时仍需重新选择原文件。

COMPLETED、CANCELLED、EXPIRED保留其终态且singleUploadTarget为null。
UPLOADING记录已超过expiresAt时，响应视图显示EXPIRED，但GET不回写数据库到期状态。
WRITE_BLOCKED允许查询元数据，返回singleUploadTarget:null；DISABLED返回403，认证版本过时返回401。
鉴权通过后，查询事务仍复核当前账号状态和authVersion，防止旧身份继续申请新凭证。

配置位于`app.media.upload-query`，与创建额度独立；通过格式校验的已认证查询均计数，包括终态和404查询。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | MEDIA_UPLOAD_QUERY_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | MEDIA_UPLOAD_QUERY_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | MEDIA_UPLOAD_QUERY_REDIS_KEY_PREFIX | fc:media: |

Redis Lua原子检查两个分钟窗口，拒绝时不消耗另一项配额；429返回Retry-After，Redis不可确认时返回503。
来源取服务端识别的TCP/可信代理地址。窗口从首次获准请求起算，修改配置需重启后端。
Redis配额在SQL事务外检查；查询事务按账号→上传会话顺序取得FOR SHARE锁，使用同一数据源复核权限和当前状态。
共享锁允许并发查询，并与账号限制或会话状态的并发修改协调；事务只查询和本地签名，不写业务表、不调用MinIO网络接口。
已经签发的存储地址仍有短期有效窗口，不能撤销已开始的存储传输；后续完成上传仍须复核会话状态和真实文件。

续签后的地址已通过真实MinIO PUT验证，保留Content-Type、Content-Length和可选校验和约束。
前端向MinIO上传时不附带业务Bearer；浏览器用原文件二进制发送，由浏览器生成Content-Length。
singleUploadTarget的OpenAPI 3.1声明已明确允许对象或null；DTO中已有业务注释保留。
查询接口复用V5表结构；取消、后台回收及完成确认见下节。后台媒体校验、素材READY及分片续传仍按后续阶段实现。

2026-11-07验证：`mvn clean verify`通过全部266项测试（152项单元/Web、114项集成），0失败、0错误、0跳过，打包成功。
本用例新增26项专项检查，覆盖原会话续签、期限裁剪、终态/过期、越权及账号限制、独立配额、并发共享锁、
认证后状态复核、原始幂等结果不变、真实MinIO恢复上传及长度/校验和约束、配置校验和OpenAPI 3.1可空模型。

### 取消本人上传与后台回收

`DELETE /api/v1/media/uploads/{uploadId}?expectedVersion=当前版本`需要Bearer身份，无JSON请求体和幂等键。
成功204且响应体为空，表示取消状态与持久化清理任务已经提交，不表示MinIO文件已物理删除。
客户端停止发送文件需另外调用XHR.abort或AbortController.abort，不能用本地中断代替服务端204确认。

UploadCancellationService及其实现归media模块。事务按账号→会话→素材加锁，复核authVersion、ACTIVE状态及本人归属。
首次取消仅允许未到期UPLOADING、expectedVersion匹配且素材WAITING_UPLOAD；条件更新会话为CANCELLED、version+1，
将素材设为已有枚举FAILED，并在同一事务插入唯一清理任务。任务入队失败时所有更新回滚，API返回503。
已经CANCELLED的本人会话重复取消返回204，包括携带第一次的旧正整数版本，不增加版本或重复创建任务。
COMPLETED、EXPIRED、超时UPLOADING、版本冲突及已READY/PROCESSING素材均返回409 UPLOAD_STATE_CONFLICT。
非法ID或整数版本超出1～2147483647返回422及对应字段提示；版本缺少或无法解析为整数返回400；非本人或不存在统一404。
WRITE_BLOCKED及DISABLED禁止取消；取消不改变账号资料版本、认证版本或其他会话。

创建接口的幂等重放也复核原上传状态与期限，取消、完成或过期后不再返回原上传凭证，避免借旧创建键继续操作。
此类终态重放使用创建接口契约已有的403 PERMISSION_DENIED；取消接口自身的状态和版本冲突仍为409 UPLOAD_STATE_CONFLICT。
查询接口对CANCELLED返回singleUploadTarget:null。完成上传接口同样拒绝CANCELLED。

配置为`app.media.upload-cancel`，计数窗口和创建、查询独立，所有合法取消请求及重复取消均计数。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | MEDIA_UPLOAD_CANCEL_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | MEDIA_UPLOAD_CANCEL_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | MEDIA_UPLOAD_CANCEL_REDIS_KEY_PREFIX | fc:media: |

复用双分钟配额Lua算法，使用独立{upload-cancel}键作用域，429附Retry-After。计数在SQL事务外完成，不因后续事务失败退还。

#### 持久化清理任务

V6新增media_upload_cleanup，按upload_id唯一，保存对象键、存储配置指纹、计划时间、尝试次数、租约令牌及安全错误码。
原V1～V5保持不变。任务首次执行时间为原会话expiresAt＋grace-seconds（默认600秒），覆盖已签发地址的有效窗口并预留在途缓冲。
UploadCleanupWorker每轮最多认领一个任务，原子更新为RUNNING；存储删除在数据库事务外进行。
执行前复核会话CANCELLED、素材FAILED、三方归属/关联/对象键及存储配置一致，限定生成的私有对象前缀，禁止误删已就绪或其他对象。
删除使用既有MinIO SDK，调用总超时10秒；不存在的对象可幂等删除，进程在删除后、回写前退出时可由过期租约恢复。
结果回写同时校验RUNNING、租约令牌及租约期限，旧执行者不能覆盖新认领结果。

配置为`app.media.upload-cleanup`：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| enabled | MEDIA_UPLOAD_CLEANUP_ENABLED | true |
| grace-seconds | MEDIA_UPLOAD_CLEANUP_GRACE_SECONDS | 600 |
| poll-delay-millis | MEDIA_UPLOAD_CLEANUP_POLL_DELAY_MILLIS | 1000 |
| lease-seconds | MEDIA_UPLOAD_CLEANUP_LEASE_SECONDS | 60，必须大于存储调用10秒上限 |
| max-attempts | MEDIA_UPLOAD_CLEANUP_MAX_ATTEMPTS | 3 |
| retry-base-seconds | MEDIA_UPLOAD_CLEANUP_RETRY_BASE_SECONDS | 5，指数退避且最多60秒 |

失败时重新排队，达到尝试上限保留FAILED；配置变化或资源状态不再允许删除时也保留失败记录，不对外暴露存储内部信息。
物理清理失败不会撤回已提交的取消结果，后台故障由任务记录和安全日志排查。当前没有公开的清理任务查询或重试接口。
取消不直接关闭浏览器到MinIO的连接；极长在途传输不受数据库状态强制中断，缓冲并非网络截止保证，后续孤立对象对账需覆盖此类残留。
后台媒体校验、READY及分片上传/AbortMultipartUpload仍是后续阶段；完成确认与媒体任务查询见下节，前端上传控件未修改。

2026-11-07验证：`mvn clean verify`通过全部306项测试（170项单元/Web、136项集成），0失败、0错误、0跳过，打包成功。
本用例新增40项专项检查，覆盖204空响应、重复取消、状态/版本/归属保护、旧创建键凭证拒绝、入队失败回滚、
认证版本复核、取消与完成条件更新竞争、独立限流、真实MinIO延迟回收、持久化重试、并发认领、租约接管及旧令牌回写拒绝。

### 确认上传完成与媒体任务查询

| 接口 | 请求 | 成功结果 |
| --- | --- | --- |
| `POST /api/v1/media/uploads/{uploadId}/completion` | Bearer、Idempotency-Key及CompleteUploadRequest | 202、TaskReceipt、Location和Retry-After |
| `GET /api/v1/tasks/{taskId}` | Bearer，无请求体 | 200、本人MEDIA_PROCESSING任务 |

当前只支持P0图片SINGLE，完成请求示例：

```json
{"expectedVersion": 1}
```

SINGLE必须省略parts，显式null或提交分片清单均返回422；视频和分片完成尚未实现。
版本必须是1～2147483647的JSON整数；错误类型或未知字段返回400，缺失、null或整数值越界返回422及body/expectedVersion提示。
路径ID和幂等键分别按契约限制校验；幂等键长度为16～128字符。

#### 存储检查与原子提交

服务先复核本人归属、当前认证版本、账号写权限、会话期限/版本及WAITING_UPLOAD素材，
保存60秒租约的完成请求预留记录，再在数据库事务外向MinIO执行HEAD检查。
对象不存在或实际大小与声明不符返回409 UPLOAD_STATE_CONFLICT，保留UPLOADING，允许补传后用原键重试；
存储Content-Type与声明不符返回415，存储/Redis/数据库不可用返回503。

检查通过后按账号→幂等记录→会话→素材的锁顺序再次复核身份、期限和源信息，
在同一事务中更新会话COMPLETED及version+1、素材PROCESSING、PENDING媒体任务和成功幂等结果。
任务入库失败则整个事务回滚。取消若先提交，完成返回409且不留下媒体处理任务；完成若先提交，取消返回409。
原会话expiresAt、对象路径和创建签名快照不变，完成后的会话查询不再签发上传凭证。

V7新增`media_processing_task`及`media_upload_completion_request`，V1～V6保持不变。
任务记录服务端获得的对象ETag、版本号、实际大小、Content-Type、修改时间及预期SHA-256，
不保存完整签名URL，查询DTO也不暴露这些内部存储字段。

HEAD只能核对存储元数据。实际文件格式、内容SHA-256和图片尺寸等仍须由后续worker检查。
旧上传签名在原有效期内仍可能覆盖源对象，后续worker必须按记录的版本/对象快照读取并复核，
将最终可用文件固定到受保护的版本或独立存储键后才可设为READY，不能把本次HEAD结果当作完整内容校验。

#### 幂等、任务与限流

完成键作用域为调用者＋completeMyUpload＋key，保留24小时，指纹包含uploadId和expectedVersion。
同键同请求重放原TaskReceipt及Location，不重复创建任务；即使任务状态随后变化，回执仍保留原PENDING受理结果。
同键不同目标或版本返回409 IDEMPOTENCY_KEY_REUSED；处理中返回409 REQUEST_IN_PROGRESS及Retry-After。
租约过期可恢复，旧租约持有者不能提交或清除新持有者的结果。新键再次完成已COMPLETED会话返回409。

完成接口采用独立分钟配额：同来源120次、同用户60次，成功幂等重放也计数；不因后续失败退还额度。
配置为`app.media.upload-completion`，Retry-After默认2秒。
任务查询采用`app.media.task-query`的独立来源120次/分钟、用户60次/分钟配额，与其他上传接口分别计数。
创建接口既有“成功幂等重放免计创建配额”规则保持不变。

TaskReceipt.statusUrl和Location均为`/api/v1/tasks/{taskId}`，可直接查询本人的MEDIA_PROCESSING任务。
其他用户、未知任务和内部邮件/清理任务ID均返回404；WRITE_BLOCKED可以读取任务，不能提交完成确认。
任务查询返回契约要求的状态、进度、错误字段及时间，不返回存储路径、凭证或内部任务记录。

本阶段没有后台媒体处理worker，任务保持PENDING、progressPercent=0、retryable=false。
202只表示持久化受理。素材查询已实现；任务执行、实际文件校验、READY推进、失败任务重试及头像绑定后续实现。

专项验证覆盖真实HTTP/MySQL/Redis/MinIO上传与HEAD、原子提交和回滚、所有权、权限变化、
状态/版本/期限、幂等重放和指纹冲突、独立限流、并发完成/取消、租约恢复及生成的OpenAPI。
全量验证日志见`.local-dev/complete-upload-full-check.log`。

2026-11-07本阶段验证：`mvn clean verify`通过385项测试（214项单元/Web、171项集成），
0失败、0错误、0跳过，打包成功；本业务新增59项专项检查，V7迁移已执行。

### 查询本人的媒体处理任务

`GET /api/v1/tasks/{taskId}`需要Bearer，无请求体和Idempotency-Key，成功200及`ApiResponse<Task>`。
taskId取完成上传返回的TaskReceipt.taskId；不使用uploadId、assetId或客户端指定的ownerId代替当前账号。

调用链为Bearer认证→MediaTaskController→MediaTaskQueryService→独立查询配额→MediaTaskQueryTransactionService。
事务读取当前账号并取得共享锁，复核authVersion及账号限制，再通过Mapper同时限定任务ID、当前ownerId和MEDIA_PROCESSING类型。
其他用户、未知任务及内部清理任务ID统一404；WRITE_BLOCKED可读，DISABLED不可读，认证后账号变化也会重新判断。

查询直接读取当前数据库状态，支持PENDING、RUNNING、SUCCEEDED、FAILED和CANCELLED，
进度、重试关联和公开错误字段均取持久化记录；不会访问MinIO、创建重试任务或改写任务/素材状态及时间。
返回字段严格由MediaTaskView构造，不序列化存储键、对象快照、签名配置或其他内部字段。
正常响应包含X-Request-Id及Cache-Control:no-store，401包含WWW-Authenticate，429包含正整数Retry-After；Swagger同步声明。

`app.media.task-query`默认同来源120次/分钟、同用户60次/分钟，配置及环境变量已在application.yml和infra/.env.example列出。
已认证且ID格式有效的查询均计数，包含404、终态和重复查询；Redis结果无法确认或数据库读取失败返回503。

当前没有媒体处理worker，新完成任务仍保持PENDING。查询支持五种状态不代表后台校验、转码或READY推进已实现。
MediaTaskQueryFlowIT使用受控数据库记录验证各状态、实时读取、查询不改数据、权限复核、任务隔离、HTTP错误及响应头。
MediaTaskQueryRateLimiterTest验证Redis故障和不确定结果，完整回归日志为`.local-dev/media-task-query-full-check.log`。

2026-11-07独立验收通过新增21项任务查询检查。`mvn clean verify`通过406项测试
（216项单元/Web、190项集成），0失败、0错误、0跳过，打包成功；本轮复用V7，无新增迁移。

### 查询本人素材校验结果

`GET /api/v1/media/assets/{assetId}`需要Bearer，无请求体和Idempotency-Key，成功200及`ApiResponse<Asset>`。
使用创建上传会话或完成确认回执中的真实assetId，不使用uploadId或taskId代替。

接口从认证上下文取得当前账号，按账号共享锁复核authVersion、账号限制及权限，
再同时限定assetId和ownerId查询素材。其他用户或未知素材统一404；WRITE_BLOCKED可读，DISABLED不可读。
返回id、ownerId、scene、state、contentType、sizeBytes、durationSeconds、width、height和createdAt，
不返回文件地址、存储键、声明文件名、SHA-256、签名参数或存储凭证。

状态按数据库如实返回WAITING_UPLOAD、PROCESSING、READY或FAILED。
实际MIME尚未知时contentType暂用创建时的声明值，实际大小尚未知时sizeBytes为0；
有实际校验值后优先使用实际值，声明大小不会被用来冒充实际大小。前端必须结合state判断是否已完成校验。
宽高没有结果时返回null；非视频durationSeconds始终为null，视频时长读取已存储的秒数。
READY记录若缺少实际MIME或实际大小，返回503 DEPENDENCY_UNAVAILABLE，不伪造READY元数据。

查询只读取持久化元数据，不访问MinIO、不计算尺寸或校验和、不推进状态，也不修改素材记录。
V8复用V5已有actual_content_type和actual_size_bytes，新增可空duration_seconds、width及height；
时长使用DECIMAL(20,6)，时长非负、宽高为正数的约束由数据库保证，V1～V7保持不变。
当前没有媒体校验worker，正常完成确认后的素材仍为PROCESSING，实际元数据由后续worker填写。
读取受控测试记录中的READY/视频元数据，不代表当前已实现媒体处理或视频上传。

独立配置`app.media.asset-query`默认来源120次/分钟、用户60次/分钟。
已认证且ID格式有效的重复读取、404和终态查询均计数，与任务查询及其他上传接口分别计数。
429附正整数Retry-After；Redis故障或结果无法确认、数据库读取失败均返回503。
所有响应包含X-Request-Id及Cache-Control:no-store，401包含WWW-Authenticate，Swagger声明同步。

专项检查覆盖元数据优先级、四种状态、空值、64位大小和精确时长、权限变化、查询不改数据、独立限流、
数据库/Redis故障、OpenAPI及真实“创建→MinIO直传→完成确认→素材查询”链路。
全量验证日志为`.local-dev/media-asset-query-full-check.log`。

2026-11-07验证：本业务新增34项检查；`mvn clean verify`通过440项测试
（223项单元/Web、217项集成），0失败、0错误、0跳过，打包成功；V8迁移已执行。

## 调用链阅读顺序

### 获取上传策略怎样阅读

1. `media/controller/MediaController.java`的getUploadPolicy方法：
   `GET /api/v1/media/upload-policy`，需要Bearer身份，返回200及统一响应包装。
2. `media/service/UploadPolicyService.java`声明接口，`media/service/impl/UploadPolicyServiceImpl.java`
   读取当前生效配置，构造只包含契约7个字段的`media/dto/UploadPolicy.java`。
3. `media/config/UploadPolicyProperties.java`绑定`app.media.upload-policy`，在启动时校验范围并复制为不可变列表；
   `MediaModuleConfiguration`注册配置。存储连接参数仍归app.storage，不进入API响应。
4. `src/test/java/com/freecontent/community/media/`按config、controller、service对应组织配置、Web及真实HTTP集成测试。

本接口不需要Mapper或数据库事务，也不请求MinIO；身份验证继续复用auth中的过滤器及会话能力。
WRITE_BLOCKED可以读取策略。这里只提供策略查询，后续实际上传和素材绑定仍须按相同策略校验。

### 创建上传会话怎样阅读

1. `media/controller/MediaController.java`的createUpload：`POST /api/v1/media/uploads`，需要Bearer及Idempotency-Key，成功201并返回Location。
2. `media/dto/CreateUploadRequest.java`及`media/validation/UploadSizeDeserializer.java`：字段白名单、用途、Unicode长度、严格整数及摘要校验。
3. `media/service/UploadService.java`及`media/service/impl/UploadServiceImpl.java`：主业务入口，依次完成限流、幂等认领、策略检查、签名准备和事务提交。
4. `media/ratelimit/UploadCreationRateLimiter.java`及`redis/rate-limit/user-hour-source-minute.lua`：用户小时窗口和TCP来源分钟窗口的原子配额检查。
5. `media/service/transaction/UploadTransactionService.java`：claim先提交60秒租约；complete复核账号和租约令牌，同事务写入素材、会话及成功结果；release只清理自己持有的未完成请求。
6. `media/storage/UploadSigner.java`：复用MinIO SDK SigV4，绑定对象、PUT方法、Content-Type及Content-Length，存在sha256时绑定x-amz-checksum-sha256；按固定时间参数重建幂等响应，不保存完整签名地址。
7. `media/entity/`和`media/mapper/`对应V5新增的media_asset、media_upload_session和media_upload_request。
8. `media/config/UploadCreationProperties.java`绑定app.media.upload-creation；StorageProperties绑定既有app.storage；MediaModuleConfiguration注册配置。

同一账号操作采用账号行→幂等行的锁顺序。准备签名不持有写事务，晚到的旧请求必须通过租约令牌校验才能提交。
会话的签名时间、TTL及配置指纹是创建响应快照，后续续签不能覆盖这些字段来改变原幂等结果。
当前仅P0图片SINGLE；素材初始WAITING_UPLOAD，尚不能用于头像或内容关联。查询续签、取消与完成确认已实现，后台媒体校验及READY处理流程仍待实现。

### 查询上传会话与恢复上传怎样阅读

1. `media/controller/MediaController.java`的getMyUpload：`GET /api/v1/media/uploads/{uploadId}`，无请求体和幂等键，成功200。
2. `media/service/UploadQueryService.java`及`media/service/impl/UploadQueryServiceImpl.java`：路径ID校验、独立查询配额及依赖异常映射。
3. `media/ratelimit/UploadQueryRateLimiter.java`和`redis/rate-limit/user-minute-source-minute.lua`：用户与来源独立分钟窗口，Redis配额在SQL事务外检查。
4. `media/service/transaction/UploadQueryTransactionService.java`的read：按账号→会话顺序取得共享锁，复核authVersion、当前状态及归属，组装当前UploadSession。
5. 同类renewTarget和signingView：按剩余有效时间裁剪TTL，用内存副本复用原存储键、大小、类型和摘要调用UploadSigner，不覆盖创建时的签名快照。
6. `user/mapper/UserAccountMapper.java`的findByIdForShare及`media/mapper/MediaUploadMapper.java`的findOwnedForShare：只查询并协调并发写入，无业务数据更新。
7. `media/config/UploadQueryProperties.java`及MediaModuleConfiguration：app.media.upload-query配置与启动校验。

查询中的版本和到期时间保持数据库原值；UPLOADING已超时只在响应视图显示EXPIRED。
终态或WRITE_BLOCKED返回singleUploadTarget:null，禁止通过GET申请新的写入凭证。
P0固定SINGLE、partSizeBytes:null、totalParts:1及uploadedParts:[]；分片列表和真正的断点续传后续实现。
本接口沿用V5表结构，不新增迁移；测试按media模块的config、service和ratelimit目录组织。

### 取消上传与文件回收怎样阅读

1. `media/controller/MediaController.java`的cancelMyUpload：DELETE本人uploadId，expectedVersion在查询参数中，成功204空响应。
2. `media/service/UploadCancellationService.java`及`service/impl/UploadCancellationServiceImpl.java`：输入校验、独立取消限流及依赖错误映射。
3. `media/service/transaction/UploadCancellationTransactionService.java`：账号→会话→素材锁序，CAS取消、素材FAILED及清理任务同事务提交。
4. `media/entity/UploadCleanupTask.java`及`media/mapper/UploadCleanupTaskMapper.java`：V6持久化清理记录，upload_id唯一，原子租约认领及令牌回写。
5. `media/job/UploadCleanupWorker.java`：到期后认领一个任务，复核取消状态、素材不可用、归属、对象键和存储配置；删除失败退避，耗尽重试保留FAILED。
6. `media/storage/UploadObjectCleaner.java`：MinIO物理删除及10秒总超时；存储I/O不持有取消事务，Bean关闭时释放HTTP资源。
7. `media/config/UploadCancellationProperties.java`与UploadCleanupProperties：app.media.upload-cancel和app.media.upload-cleanup配置；MediaModuleConfiguration注册。

后台回收默认等待原会话到期后600秒，204只确认逻辑取消与持久化入队。前端仍需单独abort文件请求。
重复取消不递增版本或重复入队；已完成上传及READY/PROCESSING素材不能通过此接口删除。
创建幂等重放同时检查原会话状态与期限，避免取消后返回旧上传凭证。

### 完成上传与媒体处理任务怎样阅读

1. `media/controller/UploadCompletionController.java`：POST完成确认，接收版本及幂等键，返回202、TaskReceipt、Location和Retry-After。
2. `media/dto/CompleteUploadRequest.java`：严格整数、字段白名单和分片模型；当前SINGLE必须省略parts。
3. `media/service/UploadCompletionService.java`与`service/impl/UploadCompletionServiceImpl.java`：输入校验、独立计数、幂等认领、真实存储检查及事务提交协调。
4. `media/service/transaction/UploadCompletionTransactionService.java`：claim短事务预留60秒租约；finish重新校验账号→幂等记录→会话→素材并原子更新两种状态、任务及幂等结果。
5. `media/storage/UploadObjectInspector.java`：复用MinIO SDK执行有超时的HEAD，区分对象缺失409与依赖故障503；网络检查不持有数据库事务。
6. `media/entity/MediaProcessingTask.java`、UploadCompletionRequestRecord及对应Mapper：V7新增持久化任务、对象快照及完成接口幂等记录。
7. `media/controller/MediaTaskController.java`：GET /api/v1/tasks/{taskId}，虽然HTTP路径是/tasks，业务及测试仍归media。
8. `media/service/MediaTaskQueryService.java`、实现及`transaction/MediaTaskQueryTransactionService.java`：复核当前身份、账号状态及任务归属，只返回`dto/MediaTaskView.java`的契约字段。
9. `media/config/`下UploadCompletion与MediaTaskQuery配置、`media/ratelimit/`对应限流器：两个独立分钟窗口，复用既有Lua算法，不修改创建重放免配额规则。

任务初始PENDING，素材PROCESSING。当前没有媒体worker，不生成进度或READY结果；真实格式、SHA-256和尺寸检查留给后续处理。
原始上传URL仍可能在到期前写入，后续worker须复核记录的对象版本/ETag/大小等快照，并固定最终可用文件，防止校验后覆盖。
`TaskReceipt`保留原PENDING受理快照；`MediaTaskView`返回任务当前状态，不暴露存储键、签名指纹或对象快照。
测试按media/config、controller、service、ratelimit组织，CompleteUploadFlowIT覆盖真实上传、并发、租约恢复及事务回滚。
`media/service/MediaTaskQueryFlowIT`独立验证任务查询：五种持久化状态、实时读取、时间不变、重试关联、
跨用户和内部任务隔离、认证后身份变化、依赖故障、独立配额及OpenAPI响应头。
`media/ratelimit/MediaTaskQueryRateLimiterTest`覆盖Redis连接故障和无法确认的脚本结果。

### 本人素材校验结果怎样阅读

1. `media/controller/MediaAssetController.java`：GET /api/v1/media/assets/{assetId}，需要Bearer，无请求体，返回200及Asset契约。
2. `media/service/MediaAssetQueryService.java`与`service/impl/MediaAssetQueryServiceImpl.java`：路径校验、独立配额和数据故障映射。
3. `media/service/transaction/MediaAssetQueryTransactionService.java`：取得当前账号共享锁，复核认证版本和可读状态，按本人范围读取素材。
4. `media/mapper/MediaAssetMapper.java`的findOwned：SQL同时限定id和owner_id，不接受客户端指定ownerId。
5. `media/dto/MediaAssetView.java`：仅输出公开元数据字段，优先使用actual值；未知MIME暂用声明值，未知实际大小为0，维度未知为null。
6. `media/entity/MediaAsset.java`映射V5实际MIME/大小及V8时长/宽高；非视频时长返回null，READY缺少关键实际值时503。
7. `media/config/MediaModuleConfiguration.java`、Properties及`ratelimit/MediaAssetQueryRateLimiter.java`：app.media.asset-query独立双分钟窗口。
8. `media/service/MediaAssetQueryFlowIT`、config/ratelimit测试及CompleteUploadFlowIT覆盖只读查询、权限、真实上传链路和故障分支。

查询不访问对象存储，不执行媒体校验或生成READY结果。V8仅新增可空元数据列与约束，历史迁移和已有素材状态不变。
