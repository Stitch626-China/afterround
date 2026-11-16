# 项目进度（唯一事实源）

> 规则：每次会话结束更新「当前状态 / 阻塞 / 下一步」；历史过程写入 `sessions/`，不在此展开。
> 多设备冲突时取并集，不删除对方状态条目。

## 快照

- 产品：职点（JobPilot），AI 求职辅助平台（简历分析 → 岗位匹配 → 模拟面试 → 面试复盘）
- 形态：个人自用版，免登录、单用户，本地 Docker Compose
- 阶段：**M1 已完成**（M1-a / b / c / d）；需求文档 **v1.7** 已确认；下一步进入 M2

## 当前状态

- 进行中：无；**M1 已由用户完成功能验收（2026-11-16）**——服务商添加（自动拉取模型 + 全局默认）、对话内模型切换、简历上传与分析均验证通过；进入 M2 前无阻塞
- 已完成：
  - **CI 已落地（2026-11-16）**：`.github/workflows/ci.yml` 三个 job——契约校验（redocly）、后端测试 + JaCoCo 门禁 + 集成测试防跳过守卫、前端构建 + Vitest + Playwright E2E；Playwright `channel` 支持 `PLAYWRIGHT_CHANNEL` 环境变量（本机 Edge / CI Chromium）。首次真实运行发现并修复两个 Linux-only 问题：`redoc` 运行时缺 `yaml` 依赖、`lint:contract` 未加载根 `redocly.yaml`。PR #1 全绿；CD 未实现
  - **CI/CD 方案核对与文档同步（2026-11-16）**：新增 `docs/ci-cd-plan.md`——CI 三 job（契约 lint / 后端测试+覆盖率门禁 / 前端构建+单测+E2E），CD 为 tag 构建后端镜像推 GHCR + 主机拉取；核对出 3 个前置改动（Playwright `channel` 需环境变量化、GHCR 镜像名必须全小写、Compose `app` 需补 `image:`）；**仅同步方案，未实现 workflow**
  - **README 多设备使用章节（2026-11-16）**：新增「主机模式 + Tailscale + 端口收敛」说明——数据只存一台主机、其余设备浏览器访问；固定 `APP_SECRET_KEY` 与绝对 `STORAGE_ROOT`；数据库/后端仅本机绑定、只对内网开放前端端口；异地用 Tailscale 虚拟内网访问；明确不建议双端各跑再同步
  - **模型配置引导体验改进（2026-11-16）**：新增服务商后自动从 Base URL 拉取上游模型（唯一模型自动添加并设全局默认，多模型勾选批量添加）；连通测试前先引导选择模型；设置页全局默认栏增加「简历分析前必须设置」说明与一键设置；简历列表/报告页模型相关错误旁新增「打开模型设置」一键入口（共享抽屉状态 composable）；后端两处无模型错误文案改为可操作指引。前端构建通过 + 6 E2E 全过（沙箱致退出码 1），后端 88 tests 与覆盖率门禁通过
  - **README 开源化补充（2026-11-16）**：补齐环境依赖、`.env` 配置表、密钥生成、本地开发与 Compose 启动、Nginx SSE 反代、常见问题；前端新增 `predev` 自动生成契约类型，保证新克隆可直接 `npm run dev`。公开前仍需补 `LICENSE` 并同步契约 license 字段
  - **M1 收口复审修复（2026-11-13）**：PENDING 手动「开始分析」；切换模型改条件原子更新；SSE 取消/队列拒绝/重启后状态回收；`LLM_MAX_TOKENS`（默认 4096）；文件删除失败保留 DB 记录；坏 SSE 帧显式报错；异步任务透传 traceId；Vitest 配置修复 + 主链路 E2E（上传/SSE/设置）；契约 schema 与 DTO 字段对齐测试；JaCoCo 门禁（总行覆盖 ≥85%、关键类 ≥95%）。**后端 86 tests 全绿、总行覆盖 91.27%，前端 9 单测 + 6 E2E 通过，契约 lint 0 error / 0 warning**
  - **M1-a 工程骨架**：`backend/`（Spring Boot 3.5.0 + Java 21 + MyBatis-Plus + Flyway + Tika + WebFlux，统一 `Result<T>` / `PageResult<T>` / `ErrorCode` / 全局异常 / traceId 过滤器 + `V1__init.sql` 八张表 + Dockerfile）；`frontend/`（Vue3 + TS + Vite + vue-router 三屏骨架 + design tokens + 契约生成 TS 类型）；根 `docker-compose.yml` + `.env.example`
  - **M1-a 接口契约**：`contracts/openapi.yaml` 26 接口 / 46 schema，redocly 0 error 0 warning
  - 需求文档 v1.7 / v1.6 / v1.5 / v1.4（信息架构与主题、薪资可见可改、F6 Offer 决策、F7 简历版本对比、F5 求职洞察、不做 RAG、Provider 数据库驱动热更新、技术选型、契约唯一源、前端设计规范、测试分层、M1 拆四阶段）
  - LLM 网关实测（new-api / Qwen3.8-27B / SSE 与 json_object 可用）
  - **Flyway V1 迁移已真实验证（2026-11-11）**：在临时 PostgreSQL 18.3 隔离实例上执行 `V1__init.sql`，8 张表、11 个命名约束全部建立；CHECK（单行表 / 会话状态 / 消息角色 / 简历分析状态）、UNIQUE（file_hash / provider+model）、FK 级联删除、`user_id=1` 等默认值均实测生效；应用启动经 Flyway 执行迁移，`flyway_schema_history` 记录 `version=1, success=t`（PG 18 仅有「Flyway 未测到 18」的升级提示，生产目标 PG 16 无此问题）
  - **Testcontainers 与 Docker 29 兼容已解决（2026-11-11）**：`backend/src/test/resources/docker-java.properties` 固定 `api.version=1.44`，`JobPilotApplicationTests` 3 个测试全部真实执行（0 跳过），Flyway 在 `postgres:16-alpine` 容器内成功迁移
  - **M1-b 后端已完成（2026-11-11）**：手写 OpenAI 兼容客户端（chat / SSE / `/models` / JSON 模式）、JSON 去围栏与结构化重试、AES-GCM 密钥加密与掩码、Provider/Model/全局默认 CRUD + 客户端缓存热更新、连通测试（回显上游真实 model）、上游模型列举与本地回退、会话 CRUD、两级切换 409 守卫、SSE 流式对话与消息追溯、`llm_call_logs` 埋点、启动种子；**后端 38 个测试全部通过**（含真实 PostgreSQL + SSE 端到端 + 契约 19 端点覆盖）
  - **M1-c 后端已完成（2026-11-12）**：简历上传（文件 / 粘贴文本）、Tika 解析与文本清洗、SHA-256 去重、`FileStorage` 本地实现与路径穿越防护、异步分析状态机（PENDING/PROCESSING/COMPLETED/FAILED，重试 ≤3）、五维加权报告（本地按权重算总分）、优势/短板/原句优化建议、3-5 个岗位方向推荐、分页/详情/删除（清理文件与分析）、Prompt 外置、**薪资字段（`V2__resume_salary.sql`）与编辑接口**（用户值优先、重分析不覆盖、清空回落 LLM 值）；**后端 50 个测试全部通过**（含真实 PostgreSQL 端到端 + 契约 26 端点全覆盖）
  - **M1-d 前端结构已完成（2026-11-12）**：一级导航只留业务主线（简历中心 + M2/M3 占位）、左下角齿轮设置抽屉（模型服务 CRUD / 全局默认 / 连通测试 / 上游列举 + 外观）、`light` / `dark` 主题（跟随系统 + 记忆 + 首屏防闪）、简历列表与上传（拖拽 / 选择 / 粘贴、状态轮询、分页、行内删除确认、点击进报告）、请求失败友好降级；浏览器实测布局 / 抽屉 / 主题切换 PASS，前端生产构建通过
  - **M1-d 收尾完成（2026-11-12）**：模型对话页（会话列表 / 新建删除 / Provider + Model 两级切换 / SSE 流式 / 消息级追溯 / 生成中状态）、`api/chat.ts` + `api/sse.ts` 纯函数解析、Vitest 9 个单测、Playwright 2 个 E2E（接口 mock 自包含，使用本机 Edge）；真实链路联调（临时 PostgreSQL 5433 + 假 OpenAI 上游）PASS：新建会话 → 发送 → 流式返回「你好，这是流式回答。」→ 状态回空闲，控制台 0 error
- 阻塞：无（原 4 项阻断与复验发现的遗留均已修复并回归验证）

## 下一步

1. 启动 M2：先补 JD / 匹配 / 模拟面试 / Offer / 简历对比的契约，再按契约实现。
2. M2 改进项：历史消息升级为「完整轮次 + token 预算 + 摘要」；契约类型/required 深度校验；SSE E2E 增量渲染断言。

## 关键决策

| 日期 | 决策 | 原因 |
|---|---|---|
| 2026-10-14 | 文件存储用本地磁盘，`FileStorage` 接口隔离 | M1 不引入 MinIO，产品化再换 |
| 2026-10-14 | 手写 WebClient 调模型，不用 Spring AI | 会话级模型绑定 + Provider 追溯 + 结构化修复 |
| 2026-10-14 | 前端 Vue3，M1 只做极简单页 | 先打通「上传 → 报告」链路 |
| 2026-10-27 | 记忆入库，git 作为跨设备同步通道 | 两台设备记忆不互通，AI 易冲突与重复劳动 |
| 2026-11-03 | Provider 与 Model 解耦：`Provider = baseUrl + apiKey`，1 个 Provider 挂 N 个 model | 同一网关下多模型不应重复配 baseUrl/key |
| 2026-11-03 | 会话内切换为 Provider + Model 两级；执行中（PROCESSING）禁止切换返回 409 | 任务执行期换模型会导致同一会话内上下文含义漂移 |
| 2026-11-03 | Provider/Model 改为**数据库驱动 + 页面 CRUD 热更新**，API Key AES-GCM 加密落库 | 用户会从内网模型切到 GPT/DeepSeek，不能每次改 yml；原 M3 的产品化项提前到 M1 |
| 2026-11-03 | M1 引入极简会话载体 `chat_sessions`/`chat_messages` | M1 无会话实体则"会话内切换"无法验证；仅作能力载体，不做业务包装，M2 面试会话复用 |
| 2026-11-03 | 技术栈定 Java 21 + Spring Boot 3.5.x + PostgreSQL + MyBatis-Plus + Flyway，单 Maven 模块 | Java 是本项目的求职作品；PG 因 JSONB 使用密集；单模块避免一人开发的多模块构建成本 |
| 2026-11-03 | **不引入** Spring AI / Spring AI Alibaba / LangChain4j / AgentScope | 真正复杂度在热更新、结构化加固、分层降级，框架都不提供且会遮蔽协议细节；LangChain4j 留作备选 |
| 2026-11-03 | 契约先行：`contracts/openapi.yaml` 为接口唯一源，前端类型由契约生成 | 前后端独立开发，必须机器保证不漂移 |
| 2026-11-03 | 前端设计基调「专业克制」：五维用横向条形图而非雷达图 | 雷达图无法精确比较、长中文标签排不下 |
| 2026-11-05 | 新增 F5 求职洞察，**优先解决"找规律"（B 场景）**，不单独立项"查具体某场" | 单次复盘价值有限，跨次规律才暴露真实短板；"查具体"由复盘文档本身承担 |
| 2026-11-05 | **不做 RAG / 向量检索** | 几十至数百场面试约 15 万字，远小于模型上下文窗口，RAG 无收益且增组件；改用结构化聚合 + PG 全文检索 + 分层摘要 + 批量 LLM 总结 |
| 2026-11-05 | 复盘文档格式定为 **Markdown**，不放图片；Web 端负责渲染好看，HTML 仅为可选二次转换 | MD 通用（Obsidian/Notion/语雀/飞书可吃）、可 diff、不污染全文索引 |
| 2026-11-05 | 层级为**三层强结构**（公司 → 岗位 → 轮次），不做无限树 | 查询与展示都清晰，避免文件系统式过度设计 |
| 2026-11-05 | 公司归一化用**联想 + 用户确认**，禁止自动合并，另提供手动「合并公司」 | 自动合并出错代价高（层级与统计全错），必须人工把关 |
| 2026-11-05 | `job_positions` 与 F2 JD 库**合并为一张表**，用 `status` 管生命周期 | 避免同一份 JD 录两遍 |
| 2026-11-05 | F5 聚合依赖**知识点标签结构化**（`topics` / `loss_tags`），纯文本无法统计 | "找规律"的前提是可聚合 |
| 2026-11-05 | 需求文档「变更记录」重写为「**迭代历程**」，改为**产品语言**叙述（多了什么 / 变了什么 / 砍了什么），技术细节降为附注 | 原表格只记技术变更，非技术读者看不出需求演进 |
| 2026-11-11 | Testcontainers 通过 `docker-java.properties` 固定 `api.version=1.44` 兼容 Docker 29 | 官方 issue #11210/#11212；零依赖变更；要求 Docker Engine ≥ 25.0 |
| 2026-11-11 | 补 `mybatis-plus-jsqlparser` 依赖 | MyBatis-Plus 3.5.9 起分页插件拆到独立模块，不引入则 `PaginationInnerInterceptor` 不存在 |
| 2026-11-11 | 会话 SSE 用 Spring MVC 返回 `Flux<ServerSentEvent>` | 复用已引入的 WebFlux；守卫异常在返回 Flux 前抛出，仍走统一 JSON 错误响应 |
| 2026-11-11 | `APP_SECRET_KEY` 缺失时启动失败 | 避免空密钥"假加密"；测试用 `src/test/resources/application.properties` 提供测试密钥 |
| 2026-11-12 | 简历分析总分由**本地按权重计算**，不采用模型自报总分 | 避免模型算错总分导致与五维不一致 |
| 2026-11-12 | 上传后**仅在已配置全局默认模型时自动触发分析** | 未配置模型时不会产生失败任务；前端仍可手动触发 |
| 2026-11-12 | JSONB 字段用自定义 `JsonbStringTypeHandler`（`setObject(Types.OTHER)`） | pgjdbc 的 `setString` 无法直接写 jsonb；避免为驱动改 compile scope |
| 2026-11-12 | 集成测试改为 **JVM 级单例容器**（`@EnabledIf` 控制无 Docker 跳过） | `@Container` 会随测试类停止容器，而 Spring 上下文跨类复用，导致后续类连已关闭端口 |
| 2026-11-12 | 测试直连 DB 清理后显式 `ProviderClientRegistry.invalidate` | 绕过了 CRUD 的缓存失效逻辑，否则会复用已停止假上游的客户端 |
| 2026-11-12 | 薪资采用「LLM 提取 + 用户可改」：用户值存 `resumes`，展示时用户值优先、清空回落，重分析不覆盖 | 简历常不写薪资，但决策必须要；用户修正值必须稳定，不能被重分析冲掉 |
| 2026-11-12 | Offer 决策（F6）归 **M2**，保留组最多 4 个；对比维度固定、缺失统一「未填写」，**不建 Skill** | 固定字段 + 固定行渲染即可保证同一行比同一项；Skill 反而引入不确定性 |
| 2026-11-12 | 前景分析默认**基于用户提供的文本/图片**，联网搜索做成开关（默认关闭） | 内网模型无联网能力；开关让用户按当前 Provider 能力决定 |
| 2026-11-12 | 模型设置从一级导航移入**左下角齿轮 → 右侧抽屉**（模型服务 / 外观） | 设置是全局偏好，不应与业务主线平级；抽屉不打断当前任务 |
| 2026-11-12 | 主题固定命名 **`light` / `dark`**，默认跟随系统、手动选择记忆、首屏前应用 | `light` 是行业标准写法；只换 token 不换布局，避免暗色下结构漂移 |
| 2026-11-12 | 简历上传后**留在列表页**，不自动跳报告 | 历史上传始终可见，失败时不会把用户带离上下文 |
| 2026-11-12 | 简历版本对比归 **M2 F7**，手动选择两份，不引入版本链 | 自动版本链会复杂化上传流程；手动对比零模型改动、覆盖跨时间对比 |
| 2026-11-12 | 场景级模型绑定归 **M2**（`llm_scene_settings`），M1 保持全局默认 | M1 仅两个场景，全局默认足够；先落需求避免过早建表 |
| 2026-11-12 | 结构化 JSON 调用固定 `temperature=0.2` | 两次简历分析重点必须一致；低温度 + 固定模板 + 结构化校验共同约束 |
| 2026-11-12 | Playwright E2E **接口全 mock、使用本机 Edge（channel: msedge）** | 自包含、无需后端与浏览器下载；主流程可在任意开发机一键跑 |
| 2026-11-12 | SSE 帧解析抽成纯函数 `extractSseEvents` | 跨 chunk / CRLF / 末尾无空行都能单测覆盖，组件只消费事件 |
