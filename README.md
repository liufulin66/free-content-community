# 免费技术内容社区

- `infra/`：本地中间件（Docker Compose：MySQL、Redis、RabbitMQ、MinIO、Mailpit，ES 按需）
- `backend/`：Spring Boot 4.1.2 + JDK 17 后端骨架
- API 契约：本地 `project-docs` 目录（不随仓库分发）

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
