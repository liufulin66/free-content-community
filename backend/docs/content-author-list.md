# 指定作者的公开作品

[返回后端入口](../README.md) · [公开列表](content-list.md) · [公开个人主页](user.md)

`GET /api/v1/users/{userId}/contents` 对应 `listUserContents`，返回 `ApiResponse<ContentPage>`。
游客可访问；携带 Token 必须验证，无法使用的凭证返回 401，认证依赖故障返回 503，不降级游客。
目标作者不存在返回 404；存在但没有公开作品返回 200 空页。目标账号被 WRITE_BLOCKED 或 DISABLED 不隐藏其既有公开作品。

## 请求与分页

- userId：1～64 位字母、数字、下划线或短横线，非法返回 422，定位 path/userId。
- limit：默认 20，范围 1～50；不可解析的整数返回 400，可解析但越界（含超大整数）返回 422。
- cursor：首次省略，最长 4096 个 Unicode 码点；空串或篡改返回 400 CURSOR_INVALID，超长返回 422，有效但过期返回 410 CURSOR_EXPIRED。
- 只允许 cursor、limit；未知或重复查询参数返回 400。kind、tagId 不属于本接口。

按 `(firstPublishedAt,id)` 稳定倒序进行键集分页，不用 offset；更新 publishedAt 不置顶。
snapshotAt 是首批生成的固定时间上界，后续翻页不变，并不代表跨请求的完整数据库快照。
后续删除、撤回、下架、素材状态变化仍会影响当前可见集合。

取 limit+1 个候选判断后续，游标记录最后扫描位置，允许不足 limit 或空批次。
hasMore=true 时 nextCursor 非空，否则严格为 null；客户端按 hasMore 继续并按 ID 去重。

## 可见性与游标

仅查询目标作者的 ACTIVE + PUBLISHED 内容，以及 published_revision_id 绑定的 APPROVED 修订。
素材必须 READY、归作者且用途正确。列表与公开详情、全站列表、公开数量共享 VISIBLE 条件；
异常发布元数据从列表排除，工作稿不参与公开投影。

items 复用 14 字段的 ContentSummary，不返回正文、imageAssetIds 或 videoAssetId，不签名 URL，不调用 MinIO HEAD。
停用标签保留发布修订的引用及当前资料；互动和头像仍沿用已确认的占位规则。

复用 app.content.public-cursor 的独立稳定密钥与固定 TTL，默认首批起 900 秒，翻页不续期。
AES-GCM 的附加认证数据绑定接口、目标作者和排序，受保护载荷也绑定目标作者、limit、调用者、快照和期限。
不能换作者、换 limit 或与全站列表交换游标。登录游标绑定用户 ID，同用户换有效会话可继续；
游客使用统一 anonymous 范围，不绑定 IP，登录/游客切换或换账号返回 400。
旧全站 v1 游标保持兼容；不新增分页上下文表、Redis 分页状态或密钥配置。

## 调用链与事务

1. [UserContentController#listUserContents](../src/main/java/com/freecontent/community/content/browse/controller/UserContentController.java)：检查查询白名单，调用 Service 并封装响应。
2. [UserContentListServiceImpl#listUserContents](../src/main/java/com/freecontent/community/content/browse/service/impl/UserContentListServiceImpl.java)：验证路径和分页参数、打开作者游标、取得共享配额、调用事务；数据库及事务异常转换为 503。
3. [PublicContentListTransactionService#list](../src/main/java/com/freecontent/community/content/browse/service/transaction/PublicContentListTransactionService.java)：在 readOnly + REPEATABLE_READ 事务中确认作者存在，查询候选并批量装配。
4. [PublicUserReader](../src/main/java/com/freecontent/community/user/service/read/PublicUserReader.java)：MANDATORY 加入同一事务，读取最小公开资料；该资料同时用于作者存在性与卡片投影。
5. [PublicContentMapper#listVisibleByAuthor](../src/main/java/com/freecontent/community/content/browse/mapper/PublicContentMapper.java)：按作者与公开条件查询；素材、标签及公开数量批量加载，复用 PublicContentAssembler。
6. PublicContentCursor#next：按最后扫描位置生成续页游标，保持原快照和期限。

正常非空页固定 5 次业务 SQL：作者资料、候选作品、批量素材、批量标签、作者公开数量。
已存在作者的空候选页 2 次，缺失作者 1 次；认证查询另计。禁止逐项调用详情 Service。
本页的作者、候选、引用和数量共享一个数据库快照。查询不修改账号、内容、修订、业务审计或 Outbox。
批量读取故障返回 503，不能吞成成功空页；仅纯投影数据异常允许按条排除并推进扫描位置。

## 配额与索引

与全站公开列表、公开详情共享 app.content.public-query / `{content-public-query}`：
默认来源 120 次/分钟，登录访问者额外 60 次/分钟；公开个人资料继续使用独立 app.user.public-query。
参数、游标非法或过期不计配额，合法空页、缺失作者和重复翻页计数。429 带正整数 Retry-After；Redis 故障返回 503。

V17__author_public_content_keyset_index.sql 新增 idx_content_author_public_seek：
`(author_id,lifecycle,publication_status,first_published_at,id)`，支持作者等值过滤后的倒序范围扫描。
EXPLAIN 测试强制该索引仅用于验证其能力，生产 SQL 保留优化器选路。
本机 V17 已执行，V1～V17 后续不可改写或删除；没有新增业务表。

固定错误契约增加第 36 个操作，原有 35 个操作与原始 openapi.json 均保留。
