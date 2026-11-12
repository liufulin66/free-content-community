# 公开媒体短期读取地址

[返回后端入口](../README.md) · [公开详情](content-browse.md) · [公开列表](content-list.md) · [作者作品](content-author-list.md)

`GET /api/v1/contents/{contentId}/media/{assetId}/access` 对应 `getPublishedMediaAccess`。
成功返回 `ApiResponse<MediaAccess>`，data 仅包含 assetId、url、expiresAt、contentType。
前端从作品卡片或详情获得素材 ID，再申请短期 URL，用于加载封面、正文图片或视频。
游客可用；携带 Token 时必须验证，无法使用的凭证返回 401，认证依赖故障返回 503，不降级游客。

## 授权与错误边界

- contentId、assetId 只允许 1～64 位字母、数字、下划线和短横线，非法返回 422，定位相应 path 参数。
- 不接受查询参数，包含 revisionId、download 或重复参数的请求返回 400，不能由客户端选择公开修订。
- 内容必须 ACTIVE + PUBLISHED，发布指针必须绑定本内容的 APPROVED 修订；工作稿与历史修订不参与授权。
- 目标素材必须真实关联当前发布修订、归该作者且用途匹配。不关联、其他作者素材、错误用途均返回 404，先于就绪状态判定。
- 目标素材非 READY 返回 409 MEDIA_NOT_READY；目标 READY 但其他关联素材异常使整篇不可见时返回 404。
- 其他可见性与完整性校验复用 PublicContentAssembler；异常发布指针、修订、时间或 READY 结果绑定返回 503。
- READY 对象必须有真实类型、大小、结果键、ETag 和当前存储身份，且键位于本人该素材的 ready 目录。仅签发结果对象，不退回 source。
- 作者或管理员身份不扩大公开范围。目标作者被禁用不自动隐藏其既有公开内容；访问者的有效 WRITE_BLOCKED 身份可读取。

仅提供短时 URL，不把桶改为公开，不写入正文。API 与签名对象响应均使用 no-store。
已签发 URL 在到期前存在访问窗口，撤回或下架会阻止后续申请，无法撤回已经下载的副本。

## 真实调用链与两次短事务

1. [PublishedMediaAccessController#getAccess](../src/main/java/com/freecontent/community/content/browse/controller/PublishedMediaAccessController.java)：拒绝查询参数，接收路径、可选身份和来源，封装响应。
2. [PublishedMediaAccessServiceImpl#getAccess](../src/main/java/com/freecontent/community/content/browse/service/impl/PublishedMediaAccessServiceImpl.java)：校验 ID、取得独立签名配额，编排 prepare → HEAD → finish；数据库及事务异常转换为 503。
3. [PublishedMediaAccessTransactionService#prepare](../src/main/java/com/freecontent/community/content/browse/service/transaction/PublishedMediaAccessTransactionService.java)：以短事务当前读，锁定内容、发布修订、引用、素材及标签，返回不可变的 Proof。
4. [MediaObjectReader#verify](../src/main/java/com/freecontent/community/media/storage/MediaObjectReader.java)：事务外发 MinIO HEAD，核对已绑定对象的存在、大小和 ETag。网络超时或对象不一致返回 503，不签名。
5. PublishedMediaAccessTransactionService#finish：新短事务重新核对完整公开授权，比较发布修订及完整对象绑定；一致后调用 [MediaReadSigner#sign](../src/main/java/com/freecontent/community/media/storage/MediaReadSigner.java) 本地生成 SigV4 GET 地址，完成后释放锁。
6. Controller 返回 JSON；浏览器使用 URL 直接读取私有对象，支持 GET Range。

两次事务显式使用 REPEATABLE_READ，Mapper 采用 FOR SHARE 当前读；HEAD 不持有数据库事务或行锁。
每轮正常授权使用 5 类查询：内容、发布修订、素材引用、按 ID 排序的批量素材、标签引用及资料。
素材批量加锁，不逐条查库。最终签名期间，内容、修订、引用和素材状态不能被并发改写。

HEAD 期间撤回、删除、移除引用或素材失效，会在 finish 重新判定并拒绝。
若新公开修订仍引用同一个结果对象，因修订绑定变化返回 503，客户端重新申请；对象键、ETag、存储、类型或大小变化同样拒绝旧校验证明。
当前公开条件已经不满足时，优先返回相应 404 或 409。

公开读取不写账号、内容、修订、素材、浏览量、敏感预览审计或 Outbox，也不创建处理任务。
既有本人预览与后台审核预览仍保留各自权限、审计和事务规则。
素材处理 worker、READY 推进及视频上传不属于本接口；取址依赖已有可信的 READY 结果绑定。

## 独立签名配额与配置

签名申请使用 app.content.public-media-access / `{content-public-media-access}`，与公开详情、全站及作者列表的浏览额度独立。
游客与登录请求共享来源窗口，登录请求额外计访问者 ID；目标作者不参与用户配额键。
非法参数或凭证不计配额；格式合法的取址尝试和重复申请计数。429 携带正整数 Retry-After，Redis 故障返回 503。

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| read-url-ttl-seconds | CONTENT_PUBLIC_MEDIA_ACCESS_READ_URL_TTL_SECONDS | 300 |
| ip-per-minute-limit | CONTENT_PUBLIC_MEDIA_ACCESS_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | CONTENT_PUBLIC_MEDIA_ACCESS_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | CONTENT_PUBLIC_MEDIA_ACCESS_REDIS_KEY_PREFIX | fc:content: |

TTL 允许 1～604800 秒，与现有签名器上限一致；配额必须为正整数，Redis 前缀不能为空或含 `{`、`}`。
非法配置阻止启动。有效期从最终签名时计算，返回的 expiresAt 与实际签名期限一致。
复用现有存储配置与签名能力，无新增依赖、密钥、业务表或迁移。V1～V17 保持原内容。
固定错误契约新增第 37 个操作，原有 36 个操作与原始 openapi.json 均保留。
