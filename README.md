# 职点（JobPilot）

AI 求职辅助平台，围绕求职全流程构建闭环：

**简历分析 → 岗位匹配 → 模拟面试 → 真实面试复盘**

一句话定位：让每一次求职动作（改简历、选岗位、练面试、复盘）都有 AI 深度参与和数据沉淀。

当前阶段为**个人自用版**：免登录、单用户、本地部署（Docker Compose 起数据库与后端，前端用 Vite 或静态托管）；数据表预留 `user_id`，不堵死后续产品化路径。

## 目录结构

```
interview-main/
├── AGENTS.md               # AI 协作契约（跨设备记忆与开工/收工流程，优先级最高）
├── contracts/              # 接口唯一真实源（openapi.yaml），前后端均以此为准
├── backend/                # Spring Boot 后端工程
├── frontend/               # Vue3 + TS + Vite 前端工程
├── docs/
│   ├── agent-memory/       # AI 记忆（随 git 同步）
│   │   ├── PROGRESS.md     # 当前进度 / 阻塞 / 下一步 / 关键决策
│   │   └── sessions/       # 每次会话一份记录（只增不改）
│   └── superpowers/
│       └── specs/          # 需求规格文档（唯一需求源）
├── reference/              # 本地参考项目（AGPL-3.0，仅本地阅读；已 gitignore，公开仓库不包含）
└── README.md
```

## 两个「唯一真实源」

| 真实源 | 管什么 | 谁依赖 |
|---|---|---|
| `docs/superpowers/specs/职点（JobPilot）需求文档.md` | **做什么**（业务规则、验收标准） | 人 |
| `contracts/openapi.yaml` | **怎么调**（路径、入参、出参、错误码、枚举） | 后端 + 前端 |

流程：先改契约 → 评审通过 → 后端实现（加契约一致性测试）→ 前端用 `openapi-typescript` 由契约生成类型，禁止手写接口类型。

### 契约怎么用

```bash
# 校验契约（必须 0 error 才算通过）
npx @redocly/cli lint contracts/openapi.yaml

# 本地预览成可读文档
npx @redocly/cli preview-docs contracts/openapi.yaml

# 前端由契约生成 TS 类型（在 frontend/ 下执行）
npx openapi-typescript ../contracts/openapi.yaml -o src/api/schema.d.ts
```

- 校验规则配置在根目录 `redocly.yaml`，其中 `no-server-example.com` 与 `operation-4xx-response` 因本地部署、无鉴权查询接口而关闭，理由写在配置注释里。
- 契约当前覆盖 **M1 全量**（模型配置 / 对话会话 / 简历）+ **M2 第一批**（场景级模型绑定 / 公司最小 CRUD / 岗位库 JD 解析与匹配 / 简历对比），F3 / F6 与 F4 / F5 随里程碑追加。
- **契约可以先于实现**：尚未实现的路径在路径层标记 `x-implemented: false`，后端契约一致性测试据此区分「已定义」与「必须已实现」；实现完成后删除该标记即可自动收紧校验。

## 环境依赖

| 依赖 | 版本要求 | 用途 |
|---|---|---|
| JDK | **21**（必须） | 后端编译与运行 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20 LTS 及以上 | 前端开发与构建 |
| npm | 10+（随 Node 安装） | 前端依赖管理 |
| Docker Engine | 25+（推荐） | 运行 PostgreSQL；集成测试要求 Docker API ≥ 1.44 |
| Docker Compose | v2（`docker compose`） | 启动数据库 / 后端 |
| PostgreSQL | 16（容器自动提供） | 也可使用本机实例，需自行建库 |
| 浏览器 | Edge / Chrome | E2E 默认使用本机 Edge |

- Windows / macOS / Linux 均可运行；下文 Windows 命令为 PowerShell 写法。
- 默认端口：后端 `8080`、前端 `5173`、PostgreSQL `5432`；端口冲突时见「常见问题」。
- 只有 JDK 21 是硬性要求；Maven、Node 版本过低会在构建阶段直接报错。

## 配置说明

### 1. 生成 .env

```bash
cp .env.example .env
```

`.env` 已被 `.gitignore` 排除，**不要提交到仓库**。至少设置以下两项：

| 变量 | 必需 | 说明 |
|---|---|---|
| `APP_SECRET_KEY` | **是** | Provider API Key 的 AES-GCM 主密钥；缺失时后端直接启动失败 |
| `DB_PASSWORD` | **是**（Compose 模式） | 与 `docker-compose.yml` 中 `POSTGRES_PASSWORD` 保持一致 |

生成 `APP_SECRET_KEY`：

```bash
# macOS / Linux
openssl rand -base64 32

# Windows PowerShell
$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

> **密钥丢失 = 已保存的 API Key 无法解密**，需要重新录入。请把 `.env` 备份到安全位置。

### 2. 全部配置项

| 变量 | 必需 | 默认值 | 说明 |
|---|---|---|---|
| `DB_URL` | 否 | `jdbc:postgresql://localhost:5432/jobpilot` | JDBC 地址；Compose 容器内为 `postgres:5432` |
| `DB_USER` | 否 | `jobpilot` | 数据库用户名 |
| `DB_PASSWORD` | 是 | 无 | 数据库密码 |
| `APP_SECRET_KEY` | 是 | 无 | AES-GCM 主密钥 |
| `STORAGE_ROOT` | 否 | `./data/files` | 上传文件落盘目录（本地 `FileStorage` 实现） |
| `ASYNC_CORE_POOL_SIZE` | 否 | `2` | 简历分析线程池核心线程数 |
| `ASYNC_MAX_POOL_SIZE` | 否 | `4` | 最大线程数 |
| `ASYNC_QUEUE_CAPACITY` | 否 | `20` | 队列容量；满时任务回滚为「待分析」并提示稍后重试 |
| `LLM_MAX_TOKENS` | 否 | `4096` | 单次 LLM 调用 token 硬上限（NFR7） |
| `LLM_MAX_HISTORY_MESSAGES` | 否 | `50` | 对话上下文最多携带的历史消息条数 |
| `LLM_BOOTSTRAP_*` | 否 | 空 | 首次启动且库内无 Provider 时导入一次种子配置；日常请在页面维护 |

> 本地开发时 Spring Boot **不会自动读取 `.env`**，需要先把变量导入当前终端，见下一节。

### 3. 模型服务（首次使用必须配置）

任意 OpenAI 兼容端点均可（公司内网网关 / OpenAI / DeepSeek 等）。两种配置方式：

1. **页面配置（推荐）**：前端左下角「设置 → 模型服务」→ 新增服务商（`baseUrl` 需包含 `/v1`）。保存后会自动从上游拉取可用模型：
   - 只有 1 个模型：自动启用并设为全局默认；
   - 多个模型：勾选后点「添加所选模型」；
   - 上游不支持模型列举：可手动填写模型名。
   全局默认用于简历分析，可随时在模型行点「设为默认」切换。
2. **启动种子**：在 `.env` 填 `LLM_BOOTSTRAP_*`，仅当数据库内没有任何 Provider 时生效一次。

未配置全局默认模型时，简历上传后会停留在「待分析」，可在列表或报告页点「开始分析」手动触发。

## 快速开始（本地开发）

```bash
git clone https://github.com/Stitch626-China/jobpilot.git
cd jobpilot
cp .env.example .env
# 编辑 .env：设置 DB_PASSWORD 与 APP_SECRET_KEY
```

### 1. 启动数据库

```bash
docker compose up -d postgres
```

首次启动会自动创建数据库并保持运行；表结构由后端启动时的 Flyway 自动创建。

### 2. 启动后端

```bash
# macOS / Linux：导入 .env 后启动
set -a && source .env && set +a
cd backend && mvn spring-boot:run
```

```powershell
# Windows PowerShell：把 .env 导入当前进程后启动
Get-Content .env | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
  $kv = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($kv[0].Trim(), $kv[1].Trim(), 'Process')
}
Set-Location backend
mvn spring-boot:run
```

- 默认端口 `8080`；健康检查 `http://localhost:8080/actuator/health`
- JDK 21 不是默认版本时，单次指定即可：PowerShell `$env:JAVA_HOME="D:\Java\21"`，macOS/Linux `JAVA_HOME=/path/to/jdk21 mvn spring-boot:run`
- Flyway 自动执行 `V1` / `V2` 迁移，无需手工执行 SQL

### 3. 启动前端

```bash
cd frontend
npm ci            # 按 package-lock.json 安装；日常也可用 npm install
npm run dev       # 启动前自动执行 gen:api，由契约生成 TS 类型
```

打开 `http://localhost:5173`；`/api` 已代理到 `http://localhost:8080`，无需额外配置跨域。

### 4. 验收

1. 「设置 → 模型服务」新增服务商，确认自动拉取的模型与全局默认，并做连通测试
2. 简历中心上传 PDF / DOCX / DOC / TXT（≤5MB）或粘贴文本
3. 等待「分析中 → 已完成」，点击卡片查看五维报告与薪资修改
4. 设置抽屉 →「打开模型对话」，验证 SSE 流式回答与生成中禁切

## Docker Compose 部署（后端 + 数据库）

```bash
docker compose up --build
```

- 启动 PostgreSQL 与后端（`http://localhost:8080`）；**当前 Compose 不包含前端**，前端需单独运行开发服务器，或把 `npm run build` 的 `dist/` 交给静态服务器。
- Compose 自动读取 `env_file: .env`，容器内 `DB_URL` 已指向 `postgres:5432`。
- 数据在 `pgdata` 卷中：`docker compose down` 保留数据，`docker compose down -v` 会清空数据库。

前端静态部署时，需要把 `/api` 反向代理到后端，并关闭 SSE 缓冲：

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;
    location / { try_files $uri $uri/ /index.html; }
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection '';
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}
```

> 若 Nginx 也跑在容器里，把 `proxy_pass` 的 `127.0.0.1:8080` 换成后端的 Compose 服务名（如 `app:8080`）。

## 多设备使用（主机模式）

用户数据由「PostgreSQL + 本地磁盘文件」组成，且免登录、单用户，**默认不做多端双向同步**。多设备使用推荐「一台主机、其余设备用浏览器访问」：数据只有一份，不会出现主键冲突、文件分叉或会话状态互相覆盖。

### 1. 选定主机并固定配置

- 选一台常开设备作为主机，运行数据库与后端；
- `.env` 的 `APP_SECRET_KEY` 固定下来并单独备份：Provider 的 API Key 用它加密落库，更换后旧密文无法解密；
- `STORAGE_ROOT` 使用**绝对路径**（如 `D:/jobpilot-data/files` 或 `/srv/jobpilot/files`），避免因启动目录不同导致上传文件散落在多个位置；
- 备份对象只有三样：数据库（`pgdata` 卷或 `pg_dump` 导出）、`STORAGE_ROOT` 目录、`.env`。

### 2. 启动主机

- 后端 + 数据库：`docker compose up -d`（或本地开发方式启动 8080）；
- 前端二选一：
  - 开发模式：`npm run dev -- --host`，监听所有网卡；
  - 生产模式：`npm run build` 后用 Nginx 托管 `dist/` 并反代 `/api`（配置见上一节）。

### 3. 端口收敛（重要）

项目**没有登录鉴权**，只能在内网或虚拟内网中使用，禁止把端口直接暴露到公网。

- 数据库只给主机本地用：把 `docker-compose.yml` 中 postgres 的 `ports` 改为 `"127.0.0.1:5432:5432"`，或直接删除该 `ports` 映射（Compose 内网仍可连通）；
- 后端 8080 不需要对其它设备开放：开发模式下由 Vite 代理、生产模式下由 Nginx 反代，均在本机转发；
- 只对局域网 / 虚拟内网开放前端端口（80 或 5173），并在主机防火墙中仅放行这些端口；
- 不要把 `5432` / `8080` 直接映射到 `0.0.0.0` 后长期运行。

### 4. 其它设备访问

同一局域网内直接访问 `http://<主机IP>:5173`（开发）或 `http://<主机IP>/`（Nginx）。

不在同一局域网时，用 Tailscale 组虚拟内网：

1. 主机与访问设备都安装 Tailscale，并登录同一账号；
2. 主机执行 `tailscale ip -4` 获取 `100.x.y.z` 地址；
3. 其它设备访问 `http://100.x.y.z:5173` 或 `http://100.x.y.z/`；
4. 可选开启 MagicDNS，用主机名代替 IP；
5. 仍按上一节收敛端口：Tailscale 只改变可达范围，不等于鉴权。

### 5. 不建议的做法

两台设备各跑一套、再同步数据库与文件。简历与分析结果通过外键强关联，双写会造成主键冲突、重复内容和状态覆盖；两台设备的 `APP_SECRET_KEY` 不一致时，另一台还读不出 API Key。确实需要多实例时，应改为共享数据库 + 对象存储（当前 `FileStorage` 仅实现本地磁盘，MinIO 属于产品化改造）。

## 常见问题

| 现象 | 原因与处理 |
|---|---|
| 启动报 `APP_SECRET_KEY 未配置` | 在 `.env` 生成并填写 `APP_SECRET_KEY` 后重启 |
| Flyway 报连接失败 / `Connection refused` | 数据库未启动或 `DB_URL` 不对；先确认 `docker compose ps` 中 postgres 为 healthy |
| `5432` 被本机 PostgreSQL 占用 | 把 `docker-compose.yml` 的 `ports` 改为 `"5433:5432"`，启动后端时设置 `DB_URL=jdbc:postgresql://localhost:5433/jobpilot` |
| 前端接口 502 / `ECONNREFUSED` | 后端未启动或不在 `8080`；先访问 `/actuator/health` |
| 上传后一直「待分析」 | 未配置全局默认模型：到设置抽屉配置，或点击「开始分析」 |
| `mvn test` 很慢 / 集成测试被跳过 | 集成测试需要 Docker；Docker 未运行时会自动跳过。Docker Engine 需 ≥25（API ≥1.44），详见下方测试说明 |
| E2E 无法启动 | Playwright 默认使用本机 Edge（`channel: 'msedge'`）；Linux/macOS 需安装 Edge，或改为 Playwright Chromium 并执行 `npx playwright install chromium` |
| Node 报 `Unsupported engine` | 升级到 Node 20 LTS 及以上 |

## M1-b 接口（模型配置与对话）

- 模型配置：`/api/llm/providers`（Provider CRUD）、`/providers/{providerKey}/models`（模型 CRUD / 上游模型列举）、
  `/providers/{providerKey}/test`（连通测试，回显上游真实 model）、`/api/llm/global`（全局默认）
- 对话会话：`/api/chat/sessions`（创建 / 分页 / 详情 / 删除）、`/sessions/{id}/model`（Provider+Model 两级切换，
  执行中返回 409/40901、已锁定 409/40902）、`/sessions/{id}/messages`（历史查询 + SSE 流式发送）
- API Key 只以 AES-GCM 密文落库、接口一律返回掩码；**启动必须配置 `APP_SECRET_KEY`**，缺失会直接启动失败
- 首次启动可用 `.env` 的 `LLM_BOOTSTRAP_*` 导入一次种子配置，之后请在模型设置页/接口维护（保存即生效）

## M1-c 接口（简历中心）

- `/api/resumes`：分页列表 / 上传（`file` 与 `text` 二选一，PDF/DOCX/DOC/TXT ≤ 5MB）
- `/api/resumes/{id}`：详情（含清洗后的解析文本）/ 删除（硬删除 + 清理磁盘文件与分析结果）
- `/api/resumes/{id}/analyze`：触发或重试分析（执行中 409/40910）
- `/api/resumes/{id}/analysis`：轮询状态与报告（五维评分、优势短板、原句 vs 优化句、3-5 个岗位方向）
- `/api/resumes/{id}/salary`：保存用户填写的当前 / 期望月薪（元/月，0~1000000）；用户值优先于 LLM 提取值，传 `null` 即清空并回落，重新分析不会覆盖用户值
- SHA-256 内容去重：重复上传返回已有记录（`duplicated=true`）且不重复触发分析
- 上传后若已配置全局默认模型会自动异步分析；未配置则保持 `PENDING`，列表与报告页都提供「开始分析」手动触发
- 单次 LLM 调用受 `LLM_MAX_TOKENS`（默认 4096）硬上限约束（NFR7）；进程重启后遗留的 `PROCESSING` 会被启动恢复自动回收
- 原始文件默认存 `app.storage.root`（`STORAGE_ROOT`，默认 `./data/files`），解析文本落库

## M1-d 前端（信息架构）

- **一级导航只放业务主线**：简历中心（M1）→ 模拟面试（M2）→ 面试复盘 / 求职洞察（M3，占位灰显）。
- **设置入口**：左下角齿轮 → 右侧抽屉，两个 Tab：
  - 模型服务：Provider / Model 增删改、全局默认、连通测试、上游模型列举，保存即生效；
  - 外观：`Light` / `Dark` 主题，默认跟随系统，手动选择后记住（`html[data-theme]` + design tokens，首屏前应用避免闪白）。
- **简历中心**：拖拽 / 选择 / 粘贴文本上传 → 留在列表观察「分析中 → 已完成」→ 点击进入报告；历史上传分页保留，删除为行内二次确认。
- **未配置全局默认模型时**，上传后不会自动分析：到设置抽屉配置后，在列表点「开始分析」即可。
- `/chat` 为 M1 模型能力验证载体，从设置抽屉进入，不占一级导航。

## 构建与测试

```bash
# 后端：单元测试 + 集成测试（含 JaCoCo 门禁：总行覆盖 ≥85%、关键类 ≥95%）
cd backend && mvn test

# 只跑部分测试时跳过覆盖率门禁（否则分母不足会失败）
cd backend && mvn -Djacoco.skip=true test -Dtest=SomeTest

# 前端：生产构建（构建前会自动重新生成接口类型）
cd frontend && npm run build

# 前端：单元测试（Vitest）与 E2E（Playwright，自带 mock，无需后端）
cd frontend && npm test
cd frontend && npm run test:e2e

# 契约校验（提交前必须 0 error / 0 warning）
npx @redocly/cli lint contracts/openapi.yaml
# 已安装前端依赖时也可用：cd frontend && npm run lint:contract
```

> 后端 `ContractSchemaTest` 还会比对契约 schema 与 DTO 字段：任意一侧漏改都会直接失败，避免接口文档与实现漂移。

> **集成测试说明**：`JobPilotApplicationTests` 用 Testcontainers 起真实 PostgreSQL 验证 Flyway 迁移，
> 需要本机 Docker 守护进程可用，并且能拉取 `postgres:16-alpine` 镜像。
> Docker 不可用或镜像缺失时该测试类会**自动跳过**（不判失败），此时迁移 SQL 尚未经过真实验证。
> Docker 29 起最低 API 版本为 1.44，而 Testcontainers 1.21 内置的 docker-java 默认用 1.32（会被 400 拒绝），
> 因此测试已在 `backend/src/test/resources/docker-java.properties` 固定 `api.version=1.44`。
> 该配置要求 **Docker Engine ≥ 25.0（API ≥ 1.44）**；更旧的 Docker 会因请求版本过高而连不上，
> 需升级 Docker 或删除该文件。后续 Spring Boot 支持 Testcontainers 2.x 后应升级并删除该配置。
> Windows + Docker Desktop 若报 "Could not find a valid Docker environment"，需把 `DOCKER_HOST`
> 指向实际管道，例如：
> `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine mvn test`

## CI（GitHub Actions）

`.github/workflows/ci.yml`：push 到 `main` 或发起 PR 时自动跑三个 job，同一分支有新提交会取消旧运行。

| Job | 内容 | 失败通常意味着 |
|---|---|---|
| 契约校验 | `npm ci` + `npm run lint:contract`（规则在根 `redocly.yaml`） | 契约有语法/规范错误，先改契约 |
| 后端 | `mvn test`（单元 + 契约 + Testcontainers 集成，含 JaCoCo 门禁，Runner 自带 Docker） | 测试或覆盖率不达标；守卫步骤会确认集成测试真实执行 |
| 前端 | `npm ci` → `npm test` → `npm run build` → 安装 Chromium → `npm run test:e2e` | 单测、类型/构建或 E2E 回归 |

- 测试范围由测试代码与 `pom.xml` / `vite.config.ts` / `playwright.config.ts` 决定，workflow 只负责执行哪条命令。
- 本机用 Edge 跑 E2E；CI 通过 `PLAYWRIGHT_CHANNEL=chromium` 切到 Linux Chromium。
- 本地复现：执行「构建与测试」里的对应命令即可；workflow 中每个 step 上方都标注了本地命令。
- 首次运行暴露的两个 Linux-only 问题（`yaml` 隐形依赖、`redocly.yaml` 未加载）及完整排查过程见 [CI/CD 方案 §9](docs/ci-cd-plan.md)。

## 文档索引

| 文档 | 说明 |
|---|---|
| [职点（JobPilot）需求文档](docs/superpowers/specs/职点（JobPilot）需求文档.md) | 唯一需求源，含功能需求、非功能需求、架构、数据模型、接口原则、里程碑 |
| [AGENTS.md](AGENTS.md) | AI 协作契约：开工 pull + 读记忆，收工写记忆 + push |
| [PROGRESS.md](docs/agent-memory/PROGRESS.md) | 当前进度、阻塞、下一步、关键决策 |
| [sessions/](docs/agent-memory/sessions/) | 会话记录：跨日/换设备新建，同日同机追加，不改已有段落 |
| [CI/CD 方案](docs/ci-cd-plan.md) | CI 已实现（契约 / 后端 / 前端三 job）；CD（GHCR 镜像 + 主机部署）规划中 |
| [项目全记录与 AI 协作方法论](docs/AI-COLLABORATION.md) | 时间线、设计决策、实现地图、协作实录、问题案例、方法论与面试包装 |

> 需求文档维护规则：每次修改必须在文内「迭代历程」章节追加记录，且**用产品语言写**——说清这一版多了什么、变了什么、砍了什么，让不看代码的人也能看懂迭代思路；技术细节只作附注。

## AI 协作与跨设备同步

项目在多台设备上开发，仓库是唯一事实源，本机 Trae 记忆仅作缓存。

- **开工**：`git pull --rebase --autostash` → 读 `AGENTS.md`、`PROGRESS.md`、最近 1~2 份会话记录，确认任务是否已被另一台设备完成。
- **收工**：更新 `PROGRESS.md` 状态三块 → 写 `sessions/YYYY-MM-DD-<主机名>.md`（跨日/换设备新建，同日同机追加）→ commit → push（失败必须重试，必要时加 `-c http.proxy=http://127.0.0.1:7890`）。
- **实时记录**：每个可验证闭环（测试/构建通过、决策、bug 修复、契约变更）先追加到当天会话记录的「实时记录」，但不必每条都 push；按任务节点统一提交。
- 会话记录只追加不改已有段落，`PROGRESS.md` 只改状态三块、冲突取并集，避免两台设备同时编辑同一文件产生冲突。
- 同一时间只在一台设备改代码；换设备前先 pull，确认 `git status` 干净。

## 技术底座

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.5.x | REST + SSE 流式输出 |
| 持久层 | MyBatis-Plus + Flyway | JSONB 用 `JacksonTypeHandler`；SQL 版本化迁移 |
| 数据库 | PostgreSQL | JSONB + CHECK 约束（状态机），预留 pgvector |
| 文件存储 | 本地磁盘 | `FileStorage` 接口隔离，产品化再换 MinIO |
| 模型网关 | 手写 WebClient（OpenAI 兼容） | **不用** Spring AI / Spring AI Alibaba / LangChain4j / AgentScope，理由见需求文档 §6.8 |
| Provider 配置 | **数据库驱动，运行时可改** | 页面 CRUD 即时生效，无需改 yml、无需重启；API Key AES-GCM 加密落库并掩码 |
| 前端 | Vue3 + TypeScript + Vite | 无 UI 库；设计规范见需求文档 §6.10 |
| 接口契约 | `contracts/openapi.yaml` | 唯一接口源，前端类型由契约生成 |
| 测试 | JUnit5 + Testcontainers + Vitest + Playwright | TDD；LLM 全 Mock |
| 部署 | Docker Compose | PostgreSQL + 应用（无 MinIO） |

## 里程碑

- **M1（MVP）**，内部四阶段：
  - M1-a 骨架与契约（已完成）：`backend/` `frontend/` `contracts/` + Spring Boot 骨架 + Docker Compose + Flyway + 统一响应/异常 + `openapi.yaml` 全量契约
  - M1-b 模型网关与 Provider 管理（已完成）：手写客户端（chat / SSE / 结构化输出）+ Provider/Model CRUD（加密落库、热更新）+ 连通测试 + 模型列举 + 会话内两级切换（执行中禁切 409）+ 对话载体 + `llm_call_logs` 埋点
  - M1-c 简历中心（轨一）：上传 / Tika 解析 / 去重 / 异步分析 / 五维报告 / 优化建议 / 岗位方向推荐 / 薪资可见可改
  - M1-d 前端与测试收口：业务导航分层 + 简历列表/上传 + 分析报告页 + 左下角设置抽屉（模型服务 / 外观 Light·Dark）+ 设计 tokens + 全量测试
- **M2**：完整前端 + JD 导入 / 匹配评分 / 行情统计 + 结构化模拟面试 + Offer 决策（多 Offer 同维度对比、保留/淘汰可反悔）
- **M3**：面试复盘（ASR / 分析 / 报告）+ PDF 导出 + Token 成本统计 + 产品化准备

## 当前进度

M1 四个阶段（骨架与契约、模型网关、简历中心、前端与测试收口）已全部完成：后端 88 个测试通过（总行覆盖 91.49%，JaCoCo 门禁通过），前端构建通过、Vitest 9 个单测 + Playwright 6 个 E2E 通过；Provider 启停热更新、MVC 异步执行器与 SSE 异常结束已加固。下一步进入 M2（JD / 模拟面试 / Offer 决策 / 简历版本对比）。详见 [PROGRESS.md](docs/agent-memory/PROGRESS.md)。

## 合规说明

`reference/interview-guide` 为 AGPL-3.0 项目，**只借鉴架构设计思想，所有代码自研**，不复制其中任何代码；该目录已加入 `.gitignore`，不纳入版本管理。
