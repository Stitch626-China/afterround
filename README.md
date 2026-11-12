# 职点（JobPilot）

AI 求职辅助平台，围绕求职全流程构建闭环：

**简历分析 → 岗位匹配 → 模拟面试 → 真实面试复盘**

一句话定位：让每一次求职动作（改简历、选岗位、练面试、复盘）都有 AI 深度参与和数据沉淀。

当前阶段为**个人自用版**：免登录、单用户、本地 Docker Compose 部署；数据表预留 `user_id`，不堵死后续产品化路径。

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
├── reference/              # 参考项目（AGPL-3.0，仅本地阅读，已 gitignore，禁止入库/复制代码）
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
- 契约当前覆盖 **M1 全量**（模型配置 / 对话会话 / 简历），F2~F5 随里程碑追加。

## 快速开始

### 1. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env：至少改 DB_PASSWORD；填 APP_SECRET_KEY（加密 Provider 密钥用）
```

### 2. 启动数据库

```bash
docker compose up -d postgres
```

日常开发只用这一条，应用在 IDE 里跑（可断点调试）。表结构由 Flyway 在应用启动时自动创建。

### 3. 启动后端

```bash
cd backend
mvn spring-boot:run
```

- 默认端口 `8080`；健康检查 `GET /actuator/health`
- OpenAPI 文档（仅用于与契约比对，不是真实源）`GET /v3/api-docs`、`/swagger-ui.html`
- **需要 JDK 21**。若本机 `JAVA_HOME` 指向其他版本，可单次指定：
  `JAVA_HOME=D:\Java\21 mvn spring-boot:run`（不要为此改系统环境变量）

### 4. 启动前端

```bash
cd frontend
npm install
npm run gen:api   # 由 contracts/openapi.yaml 生成 TS 类型（首次及契约变更后必跑）
npm run dev       # http://localhost:5173，/api 已代理到 8080
```

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
- SHA-256 内容去重：重复上传返回已有记录（`duplicated=true`）且不重复触发分析
- 上传后若已配置全局默认模型会自动异步分析；未配置则保持 `PENDING`，配置后手动触发
- 原始文件默认存 `app.storage.root`（`STORAGE_ROOT`，默认 `./data/files`），解析文本落库

## 构建与测试

```bash
# 后端：单元测试 + 集成测试
cd backend && mvn test

# 前端：生产构建（构建前会自动重新生成接口类型）
cd frontend && npm run build

# 契约校验（提交前必须 0 error / 0 warning）
npx @redocly/cli lint contracts/openapi.yaml
```

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

## 文档索引

| 文档 | 说明 |
|---|---|
| [职点（JobPilot）需求文档](docs/superpowers/specs/职点（JobPilot）需求文档.md) | 唯一需求源，含功能需求、非功能需求、架构、数据模型、接口原则、里程碑 |
| [AGENTS.md](AGENTS.md) | AI 协作契约：开工 pull + 读记忆，收工写记忆 + push |
| [PROGRESS.md](docs/agent-memory/PROGRESS.md) | 当前进度、阻塞、下一步、关键决策 |
| [sessions/](docs/agent-memory/sessions/) | 会话记录：跨日/换设备新建，同日同机追加，不改已有段落 |

> 需求文档维护规则：每次修改必须在文内「迭代历程」章节追加记录，且**用产品语言写**——说清这一版多了什么、变了什么、砍了什么，让不看代码的人也能看懂迭代思路；技术细节只作附注。

## AI 协作与跨设备同步

项目在多台设备上开发，仓库是唯一事实源，本机 Trae 记忆仅作缓存。

- **开工**：`git pull --rebase --autostash` → 读 `AGENTS.md`、`PROGRESS.md`、最近 1~2 份会话记录，确认任务是否已被另一台设备完成。
- **收工**：更新 `PROGRESS.md` 状态三块 → 写 `sessions/YYYY-MM-DD-<主机名>.md`（跨日/换设备新建，同日同机追加）→ commit → push（失败必须重试，必要时加 `-c http.proxy=http://127.0.0.1:7890`）。
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
  - M1-c 简历中心（轨一）：上传 / Tika 解析 / 去重 / 异步分析 / 五维报告 / 优化建议 / 岗位方向推荐
  - M1-d 前端与测试收口：4 屏 + 设计 tokens + 报告页视觉样板先行评审 + 全量测试
- **M2**：完整前端 + JD 导入 / 匹配评分 / 行情统计 + 结构化模拟面试
- **M3**：面试复盘（ASR / 分析 / 报告）+ PDF 导出 + Token 成本统计 + 产品化准备

## 当前进度

M1-a（骨架与契约）、M1-b（模型网关与 Provider/会话管理）、M1-c（简历中心）已完成，后端 49 个测试全部通过；下一步 M1-d 前端四屏与测试收口。详见 [PROGRESS.md](docs/agent-memory/PROGRESS.md)。

## 合规说明

`reference/interview-guide` 为 AGPL-3.0 项目，**只借鉴架构设计思想，所有代码自研**，不复制其中任何代码；该目录已加入 `.gitignore`，不纳入版本管理。
