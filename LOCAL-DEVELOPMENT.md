# 本地 Nginx 联调入口

前端目录与后端仓库保持同级：

```text
D:\Internship Program\
├─ free-content-web\          # Vue 前端，包含 node_modules
└─ free-content-community\
   ├─ dev.ps1                 # 统一命令
   ├─ start-local.cmd         # 双击启动并打开浏览器
   ├─ stop-local.cmd          # 双击停止
   ├─ .local-dev\             # 后台进程记录、日志；不提交
   ├─ backend\
   └─ infra\
      ├─ .env                 # 已有本机中间件配置；不提交
      ├─ docker-compose.yml   # 已有中间件
      ├─ docker-compose.web.yml
      └─ nginx\default.conf
```

## 启动与访问

双击 `start-local.cmd`，或者在本仓库目录打开 PowerShell：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\dev.ps1 start
```

脚本检查 Docker Desktop（未运行时尝试启动，最多等待 120 秒），构建前端，启动/复用已有中间件，然后等待 Java 健康检查，最后启动 Nginx。Java 以隐藏的后台进程运行，日志保存在 `.local-dev/backend.stdout.log` 和 `backend.stderr.log`。首次运行需要网络下载 `nginx:1.28-alpine` 和缺少的 Maven 依赖。

| 地址 | 用途 |
| --- | --- |
| <http://localhost:8088> | 前端固定入口 |
| <http://localhost:8088/login> | 登录页，直接打开/刷新可用 |
| <http://localhost:8088/swagger-ui.html> | 当前运行后端的接口文档 |
| <http://localhost:8088/actuator/health> | 经 Nginx 转发的后端健康检查 |
| <http://localhost:8088/nginx-health> | 仅检查 Nginx，自身可用时返回 ok |
| <http://localhost:8025> | Mailpit，查看后端实际发送的邮件 |

启动命令重复执行会复用健康的后端和已有容器。`8080` 已占用但健康检查失败时会报错，不会停止占用进程。脚本启动后端会执行其现有 Flyway 启动流程；本入口不新增数据库迁移。

## 日常开发命令

以下命令均在本仓库目录运行；如果终端限制脚本执行，使用上面的 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` 前缀。

```powershell
.\dev.ps1 status              # 查看 Nginx、中间件和 Java 状态
.\dev.ps1 build               # 前端改动后重新构建；随后刷新浏览器
.\dev.ps1 restart-backend     # 后端改动后，重启本脚本管理的 Java
.\dev.ps1 stop                # 停止 Nginx 和本脚本管理的 Java
.\dev.ps1 start -SkipBuild    # 复用上次 build:local 产物，减少启动等待
```

也可以双击 `stop-local.cmd`。停止保留中间件容器和数据卷；不会停止由 IDEA/其他终端启动的 Java。后端进程记录包含随机实例标识，停止前核对进程命令行，再停止该进程树，不按名称批量结束 Java。

普通 `start` 每次都会构建前端。`-SkipBuild` 只接受带 `local-build.json` 标记的真实、同源构建；普通 `pnpm build` 会清除该标记，需要重新执行 `build:local`。

### 使用 IDEA 调试后端

```powershell
.\dev.ps1 stop
.\dev.ps1 start -SkipBackend -SkipBuild
```

然后在 IDEA 中运行后端，监听 `8080`，并提供 `infra/.env` 中的环境变量。也可在另一终端进入 `backend` 执行已有 `run-local.ps1`。`-SkipBackend` 不启动或接管 Java，Nginx 与前端保持运行；Java 未启动时 API 返回 `502`，启动后自动恢复。前端不必重启，也不需要更换网址。由 IDEA 启动的后端请在 IDEA 中重启/停止。

Nginx 配置文件改动后需要重启 Nginx，例如执行 `dev.ps1 stop` 后再启动。前端构建直接更新只读挂载的 `dist` 内容，无需重启 Nginx；构建期间可能短暂无法读取静态文件，完成后刷新即可。

## 请求怎样到达后端

```text
浏览器 http://localhost:8088
  → Nginx 容器 :80
     ├─ /login、/creator/... → dist/index.html → Vue Router
     ├─ /assets/...          → dist 静态文件（不存在时 404）
     └─ /api/v1/...          → host.docker.internal:8080/api/v1/...
```

Nginx 在 Docker 内，Java 在 Windows 上，所以代理目标使用 `host.docker.internal`。容器中的 `localhost` 指容器自身。配置通过 Docker DNS（`127.0.0.11`）仅解析 IPv4，避免 Docker Desktop 返回不可达 IPv6 时出现间歇性 502；地址会定期重新解析。浏览器始终请求 `localhost:8088`，从而让普通业务 API 使用同源请求；代理保留外部 Host、查询参数、请求体及业务认证/幂等头，也保留后端的状态码与错误响应。

前端的 `pnpm build:local` 使用 Vite 编程接口明确设置 `real` 模式与空的 API 根地址。这会覆盖 `.env.local` 及继承环境中的旧值，避免产物偷偷请求 `8080` 或启用 MSW。原有 `pnpm dev`、`pnpm build` 和 `.env.local` 的用途保持不变；普通开发服务器适合需要热更新时使用。Nginx 的静态产物需要重新构建才会反映前端源代码改动。

## 验证范围与故障排查

- 页面显示“真实接口”，不使用演示账号或自动退回 Mock。`8088` 不提供 Mock worker。
- 后端尚未实现的 API 返回实际 `404`；这表示网络链路已到达后端，不代表业务接口已经可用。完整注册/创作/审核等业务验收仍取决于后端进度与真实测试身份。
- `502`：检查 Java 是否监听 `8080`，再检查 Docker Desktop 访问 Windows 宿主机的网络/防火墙。后端恢复后无需重新构建页面。
- 健康检查未 UP：查看本地 Java 日志、中间件状态；启动脚本的等待有上限，失败后可运行 `status` 查看仍在运行的服务。
- `8088` 端口冲突：结束实际占用该端口的应用，或明确修改 Compose 端口及脚本入口地址。脚本不会结束其他应用。
- 查看 Nginx 日志：`docker compose -f infra/docker-compose.web.yml logs --tail 50 nginx`。
- 本机端口只绑定回环地址；这是本地联调配置。对象存储的签名上传仍直接访问签名地址，存储 CORS 和实际上传流程需另行联调。
- 没有执行清库、删除数据卷、修改用户认证代码或增加业务接口。

2026-11-05 已在本机验证：类型检查和真实/同源构建、Nginx 配置语法、页面深链与刷新、静态资源、Swagger、API Problem 404/请求编号、Chrome 真实模式与同源请求、无 Service Worker、390px 页面无溢出；停止/启动、IDE 模式（页面 200/API 502）、恢复、重复启动复用同一后端进程、后端重启和连续 40 次代理健康检查全部通过。验证结束后服务保持运行。启动管道验证覆盖 Windows PowerShell 5 与捕获输出的调用方式，后台 Java 不会阻止启动命令正常退出。
