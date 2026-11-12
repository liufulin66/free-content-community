# 免费技术内容社区

- `infra/`：本地中间件（Docker Compose：MySQL、Redis、RabbitMQ、MinIO、Mailpit，ES 按需）
- `backend/`：Spring Boot 4.1.2 + JDK 17 后端应用
- API 契约：本地 `project-docs` 目录（不随仓库分发）

## 前后端统一启动（Nginx）

前端位于同级 `../free-content-web` 时，双击 `start-local.cmd`，或执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 start
```

打开 <http://localhost:8088>。脚本构建真实接口前端、复用中间件、启动 Java 和 Nginx；日常前端改动后执行 `dev.ps1 build`，后端改动后执行 `dev.ps1 restart-backend`。停止可双击 `stop-local.cmd`，保留中间件和数据。

IDEA 调试、日志、全部命令和请求原理见 [本地联调说明](LOCAL-DEVELOPMENT.md)。这只建立访问链路，业务 API 的可用范围仍取决于后端实现进度。

## 分别启动中间件与后端

快速开始（在仓库根目录执行）：

```powershell
# 终端一：启动中间件
cd infra
.\dev-up.ps1

# 终端二：启动后端
cd backend
.\run-local.ps1
```

健康检查：<http://localhost:8080/actuator/health>
接口文档：<http://localhost:8080/swagger-ui.html>
