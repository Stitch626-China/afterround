# 项目进度（唯一事实源）

> 规则：每次会话结束更新「当前状态 / 阻塞 / 下一步」；历史过程写入 `sessions/`，不在此展开。
> 多设备冲突时取并集，不删除对方状态条目。

## 快照

- 产品：职点（JobPilot），个人求职档案与决策系统（真实面试复盘 → 跨场归因 → 改进闭环 → Offer 决策）
- 形态：个人自用版，免登录、单用户，本地 Docker Compose
- 阶段：**M1 已完成并验收**；**M2 两批前后端全部完成**；需求文档 **v1.13** 已确认；契约 **1.9.3**（M3-a 投递档案 + M3-b B1 真实面试轮次已实现，13 个复盘/改进行动端点待实现）；M3-b 进行中（B1 完成）

## 当前状态

- **M3-b B1 真实面试轮次已实现（2026-11-23，DSL-WORKPC）**：契约升至 **1.9.3**，移除 `/api/interview-events` 与 `/api/interview-events/{eventId}` 的 `x-implemented: false`（已实现 73 / 86，13 个复盘/改进行动端点待实现）；Flyway `V8__m3_reviews.sql` 一次建好 `interview_events` / `review_sessions` / `review_analyses` / `improvement_actions` 四表（CHECK、唯一约束、级联/置空、索引）；后端新增 `modules/review`（`InterviewEvent` / `ReviewSession` 实体、mapper、`InterviewEventController` 4 端点、`InterviewEventService`），仅给 `jobPositionId` 时复用最近投递或自动创建为「面试中」并向前同步岗位，`(applicationId, roundNo)` 重复返回 40917，存在复盘禁止删除（40905），投递删除增加面试事件守卫，投递列表补 `interviewCount` / `latestRoundNo`，时间线按轮次升序返回事件。验证：`InterviewEventServiceTest` 8 + `JobApplicationServiceTest` 7 + 契约双测试通过；后端全量 **151 tests 全绿、JaCoCo 门禁通过**；`redocly lint` 0/0；真实接口验证「建公司/JD→自动解析→建投递→仅给岗位建事件复用投递→列表/统计/时间线→重复轮次 40917→PATCH 更新→删投递 409→删事件/投递/岗位/公司均 200→库内临时数据清零」，Flyway V8 在验收库执行成功。
- **M3-a 投递档案已实现（2026-11-23，DSL-WORKPC）**：契约升至 **1.9.2**（Offer / CreateOfferRequest / UpdateOfferRequest 增加 `applicationId`；简历与岗位删除补 409 说明；移除投递档案路径的 `x-implemented: false`，已实现 69 / 86）；Flyway `V7__applications.sql`（`applications` 表 + 三索引 + `offers.application_id ON DELETE SET NULL`）；后端新增 `JobApplicationController` / `JobApplicationService`（列表筛选、创建、PATCH 更新、硬删除、时间线），状态向前同步岗位（只升不降、WITHDRAWN 不改），简历 / 岗位被引用时 40905，Offer 创建/更新校验并映射 applicationId；前端新增 `/applications` 页面与导航、Offer 表单支持关联投递。验证：`JobApplicationServiceTest` 6 tests、契约全量测试、后端全量测试（行覆盖 88.43%）、Vitest 19、前端构建通过；真实接口验证「创建投递 → APPLIED 同步岗位 → 改 OFFERED 同步 → 降回 APPLIED 岗位仍 OFFERED → Offer 关联 → 时间线可见 → 删除岗位 409 → 删除投递后 Offer 解绑 → 清理成功」。
- **JD 截图导入已实现（2026-11-23，DSL-WORKPC）**：契约升至 **1.9.1**（`POST /api/job-positions` 新增 `images`，`sourceType` 增 `IMAGE`，redocly 0/0）；Flyway `V6__job_position_source_image.sql` 重建 `ck_job_positions_source_type`；后端 `importJd(file, text, images)` 三选一、最多 5 张、PNG/JPEG/WebP、单张 ≤5MB，长截图按 3000px 切片保留 300px 重叠（JDK 无法解码时整图送入），视觉 OCR 后按行合并去重，原图不落库、`sourceType=IMAGE`，失败返回 400 提示切换视觉模型或粘贴文本，`JD_OCR` 落 `llm_call_logs`；前端岗位库新增「截图」Tab（多选、缩略图、移除、5 张/5MB 校验、失败保留）。验证：`JobPositionServiceTest` 14 tests、契约测试、Vitest 19、前端构建全过；真实接口用本地渲染 JD 截图导入成功（`sourceType=IMAGE`、无原图、结构化出标题/公司/薪资/技能，`JD_OCR` 1122 tokens / 2.65s）。
- **JD 截图导入规则 v1.13（2026-11-23，DSL-WORKPC）**：JD 导入新增截图模式，最多 5 张 PNG/JPEG/WebP、单张 ≤5MB；视觉模型提取原文后复用现有结构化解析；原图不落库，只存文本；模型不支持视觉时明确提示切换模型或粘贴文本，不引入本地 OCR。**视觉探测已通过**：全局默认 `deepseek / deepseek-flash` 成功识别测试图（`OFFER_EXTRACT`，775 tokens / 8.7s），截图导入可直接复用全局默认。
- **档案链路规则 v1.12（2026-11-23，DSL-WORKPC）**：Offer 增加可空 `applicationId`；被投递引用的简历 / 岗位禁止删除（409）；投递状态向前同步岗位状态（只升不降，WITHDRAWN 不改岗位状态）。这些改动与 `applications` 建表同批落地，避免“契约有字段、数据库没有”的中间态。
- **契约 1.9.0 已定义（2026-11-23，DSL-WORKPC）**：新增 22 个 M3 1.9.0 端点——投递档案 5（列表/创建/更新/删除/时间线）、真实面试事件 4（列表/创建/更新/删除）、复盘 8（列表/创建/详情/修正/状态/重试分析/确认分析/删除）、改进动作 4（列表/创建/更新/删除）、复盘词表 1；全部标记 `x-implemented: false`。契约总数 64 → **86**；`redocly lint` **0 error / 0 warning**；`ContractCoverageTest`（86 端点）与 `ContractSchemaTest` 通过；前端契约类型重新生成与生产构建通过
- **产品重定位 v1.11（2026-11-23，DSL-WORKPC）**：需求文档升级 v1.11 —— 从「模拟面试工具」转为「个人求职档案与决策系统」，核心为真实面试复盘（≤5 分钟录入）、跨场归因与改进闭环；新增 F8 投递档案与归因链（P0）、F4/F5 升 P0，F3 模拟面试降 P2 可选、F7 降 P2；新增产品原则（≤5 分钟、必填 ≤3、不增加用户工作量）；数据模型新增 `applications` / `improvement_actions`，扩展 `interview_events` / `review_*`；M3 重排为 F8 → F4 → F5 → 收口；命名待定位确认后按五项检查
- **M2 用户验收中（2026-11-20 起，DSL-WORKPC）**：隔离验收环境（Docker PostgreSQL 16 @5433 + 后端 8080 + 前端 5173）已启动；Flyway v5、Provider 持久化正常。验收修复「打开模型对话」不跳转（抽屉 + `/chat` 路由 + E2E 断言）与「粘贴导入 JD 后列表 500 / 输入丢失」（公司名映射 null 保护 + 刷新成功才清空输入，12 tests 通过，已提交 `e094c9f` 并推送）。用户已暂停验收转向产品定位讨论；服务当前仍在运行
- **M2 第二批前端完成 + 真实链路联调通过（2026-11-19，DSL-WORKPC）**：新增 `api/interview.ts` / `api/offers.ts`、纯函数 `utils/interview.ts` / `utils/offer.ts`；页面 `InterviewListView`（方向 / 岗位 / 简历筛选创建、出题轮询、状态筛选、继续与删除）、`InterviewRoomView`（逐题作答 / 跳过、追问实时插入、两级切换、交卷、总分与分类条、逐题点评、失败重试）、`OfferView`（列表 / 对比双 Tab、文本图片提取回填、保留淘汰分组、固定 12 行对比、前景分析）；路由 `/interviews`、`/interviews/:id`、`/offers`，导航加「模拟面试 / Offer 决策」。验证：`npm run build` 通过（含契约类型生成）、**Vitest 19 全过**、**Playwright 11 E2E 全过**（新增面试全流程与 Offer 决策 2 个）；真实链路（临时 PostgreSQL 5433 + 本地假上游 3001 + 真后端 8080 + Playwright 浏览器）通过「创建面试 → 出题 → 作答 → 追问 → 跳过 → 交卷 → 评估 69 分」与「新增公司/Offer → 分析 → 对比矩阵」，临时容器与进程已清理
- **M2 第二批后端完成（2026-11-19，DSL-WORKPC）**：F3 模拟面试 11 端点 + F6 Offer 决策 8 端点全部实现。Flyway `V5__m2_batch2.sql`（`interview_sessions` 双状态机 / `interview_questions` 追问树 / `offers`）；方向 Skill 加载（`java-backend` / `ai-agent`）；异步出题、追问插入与题号顺延、交卷评估（分类加权本地总分、跳过/未答 0 分）；Offer CRUD（保留组 ≤4、显式 null 清空）、固定 12 行对比、文本/图片提取（OpenAI 视觉消息）、前景分析持久化；启动恢复扩展出题 / 评估。契约移除 19 个待实现标记（64 端点全部实现），`ContractCoverageTest` / `ContractSchemaTest` 同步。**后端 133 tests 全绿（行 89.43% / 分支 68.60%），redocly 0 error / 0 warning，前端构建通过**
- **M2 第一批真实链路联调通过（2026-11-19，DSL-WORKPC）**：临时 PostgreSQL 16（5433）+ 本地假 OpenAI 上游 + 真后端 + 前端浏览器；HTTP 全链路验证「导入 JD → 异步解析 → 公司自动关联 → 简历上传/分析 → 匹配评分 → 行情洞察 → 简历对比 → 状态/备注保存」；浏览器核对岗位列表、行情、详情页与匹配结果（总分 76 / 三维 82·78·60），无报错横幅；测试数据只存在临时库，容器与进程已清理
- **M2 第二批契约完成（2026-11-18，HOMEPC）**：契约升至 `1.8.0`，新增 **19 个端点**（F3 模拟面试 11 + F6 Offer 决策 8）、35 个 schema，端点总数 **45 → 64**、schema 116；全部标记 `x-implemented: false`。F3：方向列表、会话 CRUD、异步出题/追问、作答/跳过、交卷、评估查询、两级切换；F6：Offer CRUD、文本/图片提取（不落库）、保留/淘汰分组（≤4）、固定维度对比矩阵、前景与倾向分析。新增错误码 `40911`~`40914`；修正此前 `1.7.0` 未落盘的版本漂移。**redocly 0 error / 0 warning；ContractCoverageTest（64 端点 / 19 待实现）与 ContractSchemaTest 通过；前端契约类型生成与生产构建通过**
- **岗位跟踪增强（2026-11-18）**：F2 生命周期由笼统的「已关闭」拆为「录用 OFFERED / 淘汰 REJECTED」，新增 `remark` 自由备注（≤200 字，空字符串清空）；契约（现 `1.8.0`）、Flyway `V4__job_position_outcome_remark.sql`（含旧 `CLOSED` → `REJECTED` 迁移与状态 CHECK 重建）；岗位列表行内可写 / 改备注，详情页可编辑；后端 **129 tests 全绿（行 91.92% / 分支 76.23%）**、Vitest 9、Playwright 9 全过
- **M2 第一批前端完成（2026-11-18）**：新增 `api/jobs.ts` 与 `resumes.compareResumes`；页面 `JobListView`（列表/行情双 Tab、导入、筛选、分页、批量删除、解析轮询）、`JobDetailView`（字段修正、重试解析、匹配发起与轮询、历史匹配）、`MarketPanel`、`ComparisonView`（含入口按钮）、设置抽屉 `SceneSettings`（7 场景绑定/解绑）；路由 `/jobs`、`/jobs/:id`、`/resumes/compare`；导航加「岗位库」。验证：`npm run build` 通过（含契约类型生成）、Vitest 9 全过、Playwright **9 E2E 全过**（新增 m2-flows 3 个：岗位库筛选+行情+批量删除、简历对比、场景绑定）
- **M2 第一批后端实现细节（2026-11-17）**：Flyway `V3__m2_batch1.sql`（场景绑定 / 公司 / 岗位 / 匹配四表 + `resume_analyses.prompt_version`）；JD 解析与匹配复用「外置模板 + JSON 模式 + 本地修复 + 带错误反馈重试 ≤3 + AI DTO + 本地兜底校验」；匹配总分本地按 50/30/20 加权；对比差异本地计算、LLM 仅生成点评，失败降级；启动恢复扩展到岗位解析与匹配；V3 迁移已随 Testcontainers（postgres:16-alpine）集成测试在真实 PG 上执行
- **测试补强（2026-11-18）**：新增 `M2Batch1ApiIntegrationTest`（4 个用例，真实 PostgreSQL + 假 LLM 上游）覆盖 M2 HTTP 主链路、校验/409/401 重试 FAILED、岗位列表筛选与批量删除；新增 JD 解析与匹配「重试打满置 FAILED」及匹配 worker 异常分支单测。**后端 129 tests 全绿，行覆盖 91.90%、分支覆盖 76.11%**，门禁通过
- **测试缺口盘点（2026-11-18）**：高价值缺口已补——岗位列表分页/筛选/公司名关键字、批量删除 HTTP、匹配 worker 的三类异常分支全部覆盖；`JobPositionService` 81%→89%、`JobMatchService` 92%→94%、`JobPositionController` 58%→92%。剩余高价值缺口见「下一步」第 5 项
- **CI 状态（2026-11-19）**：main 最新提交 `205d97f` 的 CI 运行成功（run 44300034626，契约 / 后端 / 前端三 job 全绿）
- 下一步待办：M3（F4 面试复盘 / F5 求职洞察）
- **M2 契约第一批完成（2026-11-17）**：新增 12 路径 / 19 端点 / 34 schema——场景级模型绑定（7 场景枚举一次定全）、公司最小 CRUD、岗位库（JD 导入+异步解析 / 匹配评分 / 行情洞察）、简历版本对比（同步、不落库）；lint 0 error 0 warning；前端类型生成与构建通过；契约测试改为按 `x-implemented` 区分已实现与待实现（26 / 19），不再维护例外清单
- **M2 第一批契约复验（2026-11-17）**：与 M1 对比确认 0 路径/schema 删除或修改；修复 4 处问题——`info.version` 未真正升到 1.6.0、M2 错误码与后端已占用的 40903/40904/40905/40901 冲突（改为 40906/40905/40907/40908 并同步 `ErrorCode`）、`x-implemented` 机制未写入契约 `info.description`、F7 缺 Prompt 版本判定（补 `promptVersion` + `promptVersionMismatch`）。JD 解析与简历对比总结暂走全局默认、不扩 `LlmScene`（已写入契约）。lint / 前端构建 / 契约测试均通过
- 进行中（M1 遗留）：无；**M1 已由用户完成功能验收（2026-11-16）**——服务商添加（自动拉取模型 + 全局默认）、对话内模型切换、简历上传与分析均验证通过
- 已完成：
  - **实时记录规则（2026-11-17）**：`AGENTS.md` 新增「实时记录」章节——完成可验证子任务、关键决策、bug 修复、需求/契约/结构变更、上下文切换时，先追加当天会话记录的 `HH:mm` 条目（改动/原因/验证/断点）；实时记录不逐条 push，按任务节点统一提交。README 与 `项目全记录与AI协作方法论.md` 同步
  - **项目全记录与 AI 协作方法论（2026-11-17）**：新增 `docs/项目全记录与AI协作方法论.md`（547 行）——时间线与证据索引、架构与三条核心流程、20 条 ADR、代码地图、AI 协作分阶段实录与指令模式、12 个真实问题案例、十条方法论与工作流、Agent 岗位能力映射 / 演示脚本 / STAR 故事 / 高频问答，以及后续维护约定
  - **需求文档 v1.8（2026-11-17）**：把「AI 按模板思考、稳定输出」机制整理进需求文档——新增 v1.8 迭代记录；重写 §6.4 Prompt 工程规范与 §6.5 结构化输出规范（模板 system/user 分工、数据边界、JSON 模式与温度、本地修复、带错误反馈重试、AI DTO 与业务实体分离、本地计分、降级与 llm_call_logs、重试边界）；同时如实记录当前实现简化（未引入 StringTemplate、JSON 字段手写、未转义引号不本地修复）
  - **CI 已落地（2026-11-16）**：`.github/workflows/ci.yml` 三个 job——契约校验（redocly）、后端测试 + JaCoCo 门禁 + 集成测试防跳过守卫、前端构建 + Vitest + Playwright E2E；Playwright `channel` 支持 `PLAYWRIGHT_CHANNEL` 环境变量（本机 Edge / CI Chromium）。首次真实运行发现并修复两个 Linux-only 问题：`redoc` 运行时缺 `yaml` 依赖、`lint:contract` 未加载根 `redocly.yaml`（原因、修复与运行 ID 详见 `docs/ci-cd-plan.md` §9）。PR #1 全绿；CD 未实现
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
- 阻塞：本机 HOMEPC **无 Docker 与 PostgreSQL**：后端集成测试、真实链路联调无法在本机执行；契约 lint、单元测试与前端构建可正常执行（M2 第一批原 4 项阻断已修复）

## 下一步

1. **M3-b 真实面试复盘（进行中，B1 已完成）**：B1 已完成 V8 四表 + `interview-events` 4 端点 + 投递守卫/时间线；后续顺序 B2 复盘会话（文本/笔记）8 端点 + 分析 + 词表 + 改进动作 4 端点（契约剩余 13 个路径解锁）→ B3 音频上传 + ASR 适配层 + 独立线程池 + 异步状态机 → B4 前端复盘列表/详情/改进看板 → B5 全量测试 + 真实接口验证 + 文档收口。M3-c 跨场归因与 1.10.0 洞察契约随后。
2. **M2 用户验收（暂停，可继续）**：验收环境（5433 + 8080 + 5173）仍在运行；M2 两批功能已实现并修复验收问题。F3 模拟面试后续只保留为可选练习。
3. **命名与公开仓库准备**：候选名必须通过 GitHub（仓库名/Star/功能）、搜索引擎、App Store / 应用市场、中国商标网、域名五项检查，并附同名项目的功能与未做功能清单；同时补 LICENSE、差异化 README 与 5 分钟演示。
4. **M2 收口（已完成）**：两批 38 端点、前端页面、Vitest 19 / Playwright 11 与真实链路联调全部通过。
5. 非阻塞遗留：开源前补 `LICENSE` 并同步契约 `info.license`；CD 待换机稳定手动部署后再做。
6. M2 改进项：历史消息升级为「完整轮次 + token 预算 + 摘要」；契约类型/required 深度校验；SSE E2E 增量渲染断言。
7. **测试缺口（2026-11-18 盘点，高价值项已补，剩余非阻塞）**：
   - 中价值：公司 `create/update` 的并发重复键分支与标签 JSON 兜底；岗位 `importJd/parse` 的 Tika/IO 异常与循环自然退出分支；`JobMatchService.toResponse` 的详情解析失败兜底；JSON 兜底异常（`skillsJson` / `skills` / `parseList` / `write`）；`LlmGlobalSettingService` 部分分支；Provider/Model 删除的 HTTP 层；`GlobalExceptionHandler` 的 413/500。
   - 可接受不补：`LlmBootstrapRunner`（环境变量种子）、`main`、DTO、Prompt 资源缺失异常、`AesGcmCipher.deriveKey` 分支。

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
| 2026-11-17 | M2 契约**分两批**：第一批场景绑定 + 公司 + F2 岗位库 + F7 对比；第二批 F3 + F6 | 一次写 30+ 端点评审负担与返工面都大；每批 lint 通过即可评审 |
| 2026-11-17 | 契约先行用**路径级 `x-implemented: false`** 标记待实现端点 | 否则双向一致性测试必然红；标记让测试随实现自动收紧，不在测试里维护例外清单 |
| 2026-11-17 | F7 简历对比**不落库**、同步返回（本地 diff + 一次 LLM 总结，失败降级） | 需求只要手动选两份、无历史回看；少一张表与级联清理 |
| 2026-11-17 | M2 先做 **companies 最小 CRUD**；不做模糊自动合并与合并公司 | F6 要求不重复维护公司、F5 要求建表预留；模糊合并属 M3 FR5.3 |
| 2026-11-17 | F3 Skill 出题用**文件目录**（`SKILL.md` + `skill.meta.yml` + `references/`） | 需求 §6.6 已定；方向只有 2-3 个，DB 化收益低 |
| 2026-11-17 | 行情洞察**实时统计不落快照**；经验与方向按原文统计、不做归一化 | 个人数据量小，实时足够；归一化需额外词表，M3 再评估 |
| 2026-11-17 | JD 解析**回落全局默认**（可绑定场景枚举不含 JD 解析） | FRX.11 只列 7 个场景；`llm_call_logs.scene` 仍记 `jd_parse` 便于成本归因 |
