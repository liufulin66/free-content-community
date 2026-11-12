# 免费技术内容社区 · 后端

Spring Boot 4.1.2 + JDK 17 后端，已实现验证码申请、邮箱注册、密码登录、退出、验证码重置、当前账号改密、资料查询与编辑、Redis会话认证、上传策略查询、私有上传会话创建、查询续签、取消与后台文件回收、完成确认、媒体处理任务及本人素材校验结果查询，以及公开有效标签查询、管理员创建标签、详情与列表查询、修改及启停标签、创建内容及首个草稿、读取本人内容工作区、保存当前可编辑草稿、本人创作及回收站分页查询、本人修订媒体预览，对应本地 API 契约（`project-docs` 目录，不随仓库分发）。

## 技术栈

| 组件 | 版本/说明 |
|---|---|
| Spring Boot | 4.1.2 |
| JDK | 17（Spring Boot 4 基线也是 17） |
| Web | `spring-boot-starter-webmvc`（Boot 4 已弃用 `spring-boot-starter-web`） |
| ORM | MyBatis-Plus `mybatis-plus-spring-boot4-starter` 3.5.18 |
| 数据库 | MySQL 8.0（Docker，宿主机端口 3307） |
| 迁移 | Flyway（`src/main/resources/db/migration`） |
| 缓存/会话 | Redis 7（Docker，宿主机端口 6380） |
| 消息 | RabbitMQ 4（Docker，5672 / 15672） |
| 对象存储 | MinIO（Docker，9000 / 9001） |
| 接口文档 | springdoc-openapi 3.1.1，OpenAPI 3.1 |

## 启动前

先启动中间件（在仓库根目录执行）：

```powershell
cd infra
.\dev-up.ps1
```

确认 `docker compose ps` 中 MySQL、Redis、RabbitMQ、MinIO、Mailpit 均为 healthy。

## 启动后端

方式一（推荐，自动读取 `../infra/.env`，在仓库根目录执行）：

```powershell
cd backend
.\run-local.ps1
```

方式二（手动设置环境变量后）：

```bash
mvn spring-boot:run
```

需要设置：`MYSQL_PASSWORD`、`REDIS_PASSWORD`、`RABBITMQ_PASSWORD`、`EMAIL_CHALLENGE_MAC_KEY_BASE64`、`EMAIL_CHALLENGE_ENCRYPTION_KEY_BASE64`；媒体上传还需要 `MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD`。`run-local.ps1` 会加载 `infra/.env`；复制的 `.env.example` 已包含本地示例密钥，生产必须重新生成。
前端入口 `http://localhost:8088`（nginx）和 `http://localhost:5173`（Vite）已加入默认 CORS 白名单；换端口时通过 `CORS_ALLOWED_ORIGINS` 覆盖并重启后端。

## 验证

| 地址 | 说明 |
|---|---|
| <http://localhost:8080/actuator/health> | 数据库、Redis、RabbitMQ 健康状态 |
| <http://localhost:8080/v3/api-docs> | OpenAPI JSON |
| <http://localhost:8080/swagger-ui.html> | Swagger UI |

## 业务与阅读入口

| 业务 | 详细行为与调用链 |
| --- | --- |
| 认证与会话 | [认证与会话](docs/auth.md) |
| 账号与资料 | [账号与资料](docs/user.md) |
| 上传与媒体 | [上传与媒体](docs/media.md) |
| 标签 | [标签](docs/tag.md) |
| 内容创作与工作区 | [内容创作与工作区](docs/content-workspace.md) |
| 内容审核 | [内容审核](docs/content-review.md) |
| 已发布内容浏览 | [公开详情](docs/content-browse.md) · [公开列表](docs/content-list.md) · [作者作品](docs/content-author-list.md) · [媒体取址](docs/content-media-access.md) |
| 接口错误响应 | [接口错误响应](docs/api-errors.md) |
| 开发与验证 | [开发与验证](docs/development.md) |

目录、职责及事务边界见 [STRUCTURE.md](STRUCTURE.md)，业务分包规则见 [AGENTS.md](../AGENTS.md)。

## 开发约定

- API 以原始 `openapi.json` 为准，本文档解释实现行为；详细业务约定见上表。
- 成功响应统一封装，204 无响应体；失败使用 RFC 9457 Problem Details。
- 时间使用 UTC；业务配置及环境变量保持独立，按模块统一注册。
- Flyway 已执行的 V1～V17 不得改写或删除，后续变化新增迁移。
- `mvn test` 运行单元/Web 测试；`mvn clean verify` 还运行真实 MySQL、Redis、MinIO 集成测试。
- 集成测试必须串行；静默加载 `infra/.env` 后关闭邮件投递及上传清理，再执行验证。

## 已实现与后续范围

认证、当前账号资料、公开个人主页、作者公开作品列表、公开媒体取址、标签、P0 图片 SINGLE 上传、内容工作区及审核提交/查询/决定/撤销、已发布内容详情和公开列表已实现。
媒体校验 worker 与 READY 推进、视频/分片、完整修订历史、删除恢复、Outbox 投递与消费尚待实现。
