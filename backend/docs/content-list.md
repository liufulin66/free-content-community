# 浏览公开内容列表

[返回后端入口](../README.md) · [公开详情](content-browse.md) · [结构说明](../STRUCTURE.md)

## 接口与参数

```http
GET /api/v1/contents?limit=20&kind=ARTICLE&tagId=tag_example
Authorization: Bearer <有效凭证，可省略整个请求头>
```

operationId 为 `listPublicContents`，允许游客；携带凭证时必须验证，无法使用的凭证返回 401，不降级游客。
只接受 cursor、limit、kind、tagId，未知或重复参数返回 400，非法参数在业务限流前拒绝。

| 参数 | 规则 |
| --- | --- |
| limit | 默认20，1～50；无法解析为整数400，可解析但超界422 |
| kind | 可省略；仅ARTICLE或VIDEO，非法值422 |
| tagId | 可省略；1～64位字母、数字、下划线、短横线；非法值422；未知合法ID返回空页 |
| cursor | 首次省略；最长4096字符，超长422；空字符串、篡改、绑定不匹配400 CURSOR_INVALID；过期410 CURSOR_EXPIRED |

筛选标签只匹配当前发布修订的引用，不读取工作稿标签；已引用的停用标签仍可筛选和展示。

## 分页与可见性

按 `(firstPublishedAt,id)` 倒序执行键集分页，不使用 offset，不按更新后的 publishedAt 重新置顶。
首批响应生成 snapshotAt 上界，后续页面保留同一上界，排除首次发布时间晚于该上界的内容。
同一首次发布时间使用ASCII二进制ID顺序打破并列。

公开条件与详情、作者公开数量共用：ACTIVE、PUBLISHED、正确归属的APPROVED发布修订、完整发布时间、
非空白标题和用途/作者归属正确的READY素材。VIDEO需要视频引用，ARTICLE不能关联视频。
异常发布指针、缺失时间、非公开状态和不满足公开条件的记录被排除，不因为一条坏数据阻断列表。
详情原有完整性异常503的行为保留；数据库、批量关联读取等依赖故障仍返回503，不能伪装成空页。

返回 data 包含且必须包含 items、nextCursor、hasMore、snapshotAt。
items 使用契约 ContentSummary，仅14个卡片字段，不包含正文、正文图片列表、视频ID或工作区数据。
摘要、停用标签、作者资料与已确认的互动/头像字段边界沿用公开详情。

- hasMore=true时nextCursor为非空字符串；hasMore=false时必须为null，不能返回空字符串。
- 通过limit+1判断后续扫描，游标按最后扫描位置推进；即使纯投影排除了异常条目，也能继续翻页。
- 允许本批少于limit甚至为空，客户端应依据hasMore继续，并按资源ID去重。
- 游标是快照上界，不是跨请求的完整数据库快照；删除、撤回、下架、素材状态改变会影响后续可见集合。
- 本页的条目、引用、作者资料和作者数量处于同一只读REPEATABLE_READ事务。作者数量反映该事务中的当前公开集合。
- 读取不增加浏览量、内容版本、审核记录、审计或Outbox，响应统一Cache-Control: no-store。

## 游标、安全与配置

游标使用JDK已有AES-GCM能力，并采用内容浏览独立的32字节密钥。随机nonce避免重复密文，认证标签检测篡改；
附加认证数据绑定接口及固定排序。验证密文后才信任筛选、limit、调用者、原快照、过期时间和末次扫描位置。
协议不依赖Redis分页状态，不把业务流程迁入auth；新增实现位于content.browse.cursor。

登录状态绑定用户ID，同一用户换有效会话仍可使用；换账号、游客与登录状态切换返回400。
游客共享匿名范围，不绑定IP；切换网络不影响游标，但新请求仍受其来源配额约束。
有效期从首次请求起固定15分钟，后续签发下一页游标不续期。

| 参数 | 环境变量 | 默认/要求 |
| --- | --- | --- |
| app.content.public-cursor.ttl-seconds | CONTENT_PUBLIC_CURSOR_TTL_SECONDS | 900，可配置1～86400 |
| app.content.public-cursor.key-base64 | CONTENT_PUBLIC_CURSOR_KEY_BASE64 | 独立随机32字节Base64密钥，稳定保存、多实例一致 |

本机infra/.env已静默补齐独立密钥且保留原有内容，代码和日志不输出密钥。
新环境部署应生成自己的随机密钥，不能把.env.example演示值用于生产；修改密钥会使旧游标失效。

列表与详情共享app.content.public-query及{content-public-query}：来源120次/分钟，登录用户额外60次/分钟，
与创作区配额隔离。非法/过期游标不计数，合法空页和重复翻页计数；429提供正整数Retry-After。

## 结构与查询数量

- [PublicContentListService](../src/main/java/com/freecontent/community/content/browse/service/PublicContentListService.java)及service.impl负责解析、游标验证、限流和事务编排。
- [PublicContentListTransactionService](../src/main/java/com/freecontent/community/content/browse/service/transaction/PublicContentListTransactionService.java)负责键集扫描与批量投影。
- [PublicContentMapper](../src/main/java/com/freecontent/community/content/browse/mapper/PublicContentMapper.java)共用可见性SQL，批量读取素材、标签及作者数量。
- user的PublicUserReader/PublicUserMapper提供批量公开资料；不读取密码、邮箱或完整账号实体。
- [PublicContentAssembler](../src/main/java/com/freecontent/community/content/browse/service/read/PublicContentAssembler.java)让列表与详情复用相同纯投影规则，禁止逐项调用详情Service。

正常非空页面固定5次业务SQL：候选项、素材、标签、作者资料、作者数量；空候选页只执行候选查询。
业务层每次最多取回limit+1个候选项；关联加载不产生N+1查询，数据库扫描量由筛选条件、数据分布和执行计划决定。
V16新增idx_content_public_seek(lifecycle,publication_status,first_published_at,id)，支持按既定顺序反向扫描，
生产SQL保留优化器选路。V1～V15不改写，V16执行后也不得修改；没有新增分页上下文表。
固定错误契约新增第34个操作；原始OpenAPI保持不变。

## 本轮验证记录

2026-11-11 按红→绿→重构验证游标、HTTP入口、真实分页、条件游标结构及生成的OpenAPI。
补充并修复Java与MySQL空白字符语义差异，确保列表过滤与作者公开数量一致。

最终在源码哈希一致的 `.local-dev/structure-verification/list-final-v2/backend` 运行
`mvn -B -ntp clean verify`：1854项全部通过，其中841项单元/Web、1013项真实集成，
零失败、零错误、零跳过，打包成功；本轮新增49项检查。
覆盖固定排序、快照上界、筛选/用户绑定、到期不续期、空页、异常过滤、固定5次批量查询、
翻页时撤回、同页并发快照、数据库故障和34个操作的错误契约。

V16已成功执行，EXPLAIN确认索引支持反向扫描；V1～V15保持原内容。
完整日志 `.local-dev/structure-list-final-v2.log`，一致性与统计记录 `.local-dev/public-list-verification-summary.json`。
本轮未提交、推送或启动独立开发后端。
