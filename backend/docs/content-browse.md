# 已发布内容浏览

[返回后端入口](../README.md) · [结构与边界](../STRUCTURE.md)

## 查看已发布内容详情

```http
GET /api/v1/contents/{contentId}
Authorization: Bearer <有效凭证，可省略整个请求头>
```

operationId 为 `getPublicContent`，成功返回 200 和 `ApiResponse<PublicContent>`。
不接受查询参数；contentId 为 1～64 位字母、数字、下划线或短横线。
未知查询参数返回 400，非法路径 ID 返回 422，二者在计数前拒绝。

游客可用。提供 Authorization 时必须验证格式、会话及账号当前状态，失效或无法使用的凭证返回
401 AUTHENTICATION_REQUIRED，不降级游客。认证依赖故障返回 503。
可选认证只放行精确的 GET 详情路径；创作、后台和后续媒体授权路径沿用各自权限规则。
WRITE_BLOCKED 凭证可读取；作者账号被禁用不会自动改变其已有内容的发布状态。

## 可见性与错误边界

- 只从 `published_revision_id` 读取当前已审核公开快照，不读取 working_revision，不使用当前工作稿替代。
- lifecycle 必须 ACTIVE，publicationStatus 必须 PUBLISHED，修订必须属于该内容且为 APPROVED。
- 未发布、删除、撤回、下架及非 APPROVED 修订统一 404；作者或管理身份不能扩大此接口范围。
- 当前发布修订的封面、正文图片和视频必须归作者、用途匹配且为 READY；否则 404。
- VIDEO 必须有发布视频，ARTICLE 不可引用视频；资源顺序按修订引用的 position 保留。
- 标记已发布但缺少发布指针、绑定修订、发布时间，或关联数据无法完整投影时返回 503。
- 数据库、Redis 故障返回 503，不输出 SQL、连接或存储信息。

详情仅返回素材 ID，不签发媒体地址、不访问 MinIO HEAD、不退回 source 对象。
公开媒体访问地址由[独立的媒体取址接口](content-media-access.md)提供，需要单独授权和真实对象核验。

## 响应字段

PublicContent 严格包含 id、kind、title、summary、author、coverAssetId、tags、firstPublishedAt、
publishedAt、publishedRevisionId、likeCount、commentCount、viewerHasLiked、viewerHasBookmarked、
bodyMarkdown、imageAssetIds、videoAssetId。可空字段仍显式返回 null。

作者资料使用 PublicUser：id、displayName、bio、avatarUrl、createdAt、publishedContentCount、followerCount。
只查询用户公开字段，不读取或返回密码、邮箱、认证版本和角色。

- firstPublishedAt 保留首次发布的时间；publishedAt 是当前公开版本发布时间，均使用 UTC。
- summary 复用现有 CommonMark 语法树提取文本，忽略图片与原始 HTML 节点，保留代码文本、合并 Unicode 空白，
  截取前 300 个 Unicode 码点，不截断 UTF-16 代理对；空正文返回空摘要。
- tags 保留已发布修订的引用与顺序，返回标签当前名称、说明、状态和版本。停用标签仍返回 DISABLED，不因此隐藏已发布内容。
- 作者公开作品数按真实可见的发布内容及素材条件计算，排除未公开、撤回、下架、已删除、未批准或素材未 READY 的内容。
- 点赞、评论、收藏、关注和 READY 头像绑定尚未实现：当前互动计数及关注者数为 0，两个个性化标志为 false，avatarUrl 为 null。
  本次没有增加相应关系表、计数投影或写接口，后续模块实现时接入真实互动读取。

读取不递增浏览计数、内容版本，不修改业务行、审核、审计或 Outbox。
所有响应使用 Cache-Control: no-store，避免可选认证字段进入公共缓存。

## 限流配置

独立使用 `app.content.public-query`，不与本人工作区/列表/审核反馈的 `app.content.query` 共用配额。

| 参数 | 环境变量 | 默认值 |
| --- | --- | --- |
| ip-per-minute-limit | CONTENT_PUBLIC_QUERY_IP_PER_MINUTE_LIMIT | 120 |
| user-per-minute-limit | CONTENT_PUBLIC_QUERY_USER_PER_MINUTE_LIMIT | 60 |
| redis-key-prefix | CONTENT_PUBLIC_QUERY_REDIS_KEY_PREFIX | fc:content: |

游客只消耗来源额度；登录用户额外消耗账号额度，两者共享同一来源计数。
键使用 `{content-public-query}` hash tag，身份与来源经过摘要处理。
正常读取、合法 ID 的 404 和重复读取均计数；拒绝时不继续增加其他配额。
429 提供正整数 Retry-After，Redis 无法确认配额时返回 503。

## 调用链与分包

1. [PublicContentController](../src/main/java/com/freecontent/community/content/browse/controller/PublicContentController.java)：HTTP 入口与严格查询参数边界。
2. [PublicContentService](../src/main/java/com/freecontent/community/content/browse/service/PublicContentService.java)及 service.impl：路径校验、独立限流、事务编排和依赖错误转换。
3. [PublicContentTransactionService](../src/main/java/com/freecontent/community/content/browse/service/transaction/PublicContentTransactionService.java)：只读 REPEATABLE_READ 快照，校验可见性并组装响应。
4. [PublicContentMapper](../src/main/java/com/freecontent/community/content/browse/mapper/PublicContentMapper.java)：只连接发布修订，批量读取素材/标签引用及计算公开数量，不逐素材查询。
5. [PublicUserReader](../src/main/java/com/freecontent/community/user/service/read/PublicUserReader.java)：加入调用方事务读取公开资料，内容统计由内容模块计算。
6. browse 内的 config、ratelimit、dto、ContentSummary 及对应测试共同归属此用例。

browse 不依赖 workspace/review 业务实现；只使用内容共享类型和现有认证、通用限流能力。
原有 V1～V15 及原始 OpenAPI 不改写，固定错误契约新增 getPublicContent，合计 33 个已实现操作。

## 本轮验证记录

2026-11-11 按红→绿→重构完成摘要、可选认证端点和真实业务读取；补充限流、依赖故障、
并发快照和原始契约模型核对。新接口与作者公开作品数在同一数据库快照中保持一致。

最终在源码哈希一致的 `.local-dev/structure-verification/public-final/backend` 执行
`mvn -B -ntp clean verify`：1805 项通过，其中 821 项单元/Web、984 项真实集成，
零失败、零错误、零跳过，打包成功。新增 63 项检查；集成测试使用真实 MySQL、Redis、MinIO 环境，串行运行。
详情自身只访问数据库和限流/认证 Redis，不为返回资源 ID 发起对象存储网络请求。

完整日志：`.local-dev/structure-public-final.log`；统计及一致性核对：`.local-dev/public-content-verification-summary.json`。
V1～V15、原始 OpenAPI、既有应用配置值、IDE 设置和原有 32 个错误契约均保留。
本轮没有提交、推送或启动独立开发后端。
