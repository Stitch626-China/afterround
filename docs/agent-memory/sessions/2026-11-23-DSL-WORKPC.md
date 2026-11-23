# 2026-11-23 DSL-WORKPC

## 目标

继续 M2 第二批用户验收：启动隔离环境，修复验收中发现的问题。

## 改动（文件 → 说明）

- `backend/src/main/java/com/jobpilot/modules/job/service/JobPositionService.java`：岗位列表映射公司名时先判空 `companyId`，避免未关联公司的岗位触发 NPE。
- `backend/src/test/java/com/jobpilot/modules/job/service/JobPositionServiceTest.java`：新增 `pageHandlesJobsWithoutCompanyAssociation` 回归用例。
- `frontend/src/views/JobListView.vue`：导入成功后先刷新列表，刷新成功才清空粘贴文本并收起面板；任何失败都保留用户已填 JD 文本。

## 决策（为什么）

- 验收继续使用隔离容器 `jobpilot-verify-pg`（127.0.0.1:5433），保留 11-20 的验收数据与模型配置，不影响本机 5432 的系统 PostgreSQL。
- 粘贴导入的岗位在解析完成前 `companyId` 为 null；列表接口必须容忍该状态，而不是等解析完成才能展示。

## 未完成 / 下一步

1. 用户继续验收 M2 第一批 / 第二批；后续问题继续按实时记录追加。
2. 验收结束后清理 `jobpilot-verify-pg`，同步 PROGRESS 并 commit + push。

## 阻塞

无。

## 实时记录

### 10:19 验收环境重新启动
- 改动：启动 Docker Desktop 与 `jobpilot-verify-pg`（5433）；后端 8080（`DB_URL` 指向验收库）；前端 Vite 5173。
- 原因：用户继续验收 M2 第二批。
- 验证：health `UP`；Flyway 仍为 v5；Provider `datangsoft / 大唐软件API / Qwen3.8-27B（全局默认）` 持久化可读；前端 5173 返回 200。上次未推送提交 `794ab82` 已直连补推成功（`origin/main..main = 0`）。
- 下一步 / 断点：用户开始验收。

### 10:35 粘贴导入 JD 报「服务器内部错误」修复
- 改动：`JobPositionService.page` 对公司名映射加 null 判断并补单测；`JobListView.submitImport` 改为列表刷新成功后才清空输入。
- 原因：粘贴导入创建的岗位在异步解析完成前 `companyId=null`，当页所有岗位都未关联公司时 `companyNames` 返回不可变空 `Map`，`names.get(null)` 抛 NPE → 列表接口 500；前端在导入成功后先清空文本再刷新列表，于是用户既看到报错又丢失输入。
- 验证：
  - `JobPositionServiceTest` **12 tests 全绿**（新增无公司关联分页用例）。
  - 前端构建通过（含契约类型生成）。
  - 真实接口复现：粘贴导入创建 `id=2`（`companyId=null`、`PROCESSING`）后立即查询列表返回 **200** 且包含该记录；复现后已通过接口删除该测试记录。用户的 `id=1`（后端开发工程师，已解析）数据未受影响。
- 下一步 / 断点：用户重试导入并继续验收第二批。

### 14:56 产品重定位为「求职档案与决策系统」（需求 v1.11）
- 改动：需求文档升级 v1.11 —— 新增 F8 投递档案与归因链（P0）；F4 真实面试复盘升 P0（≤5 分钟录入 + 结构化 + 改进行动）；F5 升 P0 并改为「跨场归因与改进闭环」；F3 模拟面试降 P2 可选练习；F7 降 P2；新增产品原则（≤5 分钟、必填 ≤3、不为了数据完整增加工作量）；数据模型新增 `applications` / `improvement_actions`，扩展 `interview_events` / `review_sessions` / `review_analyses`；M3 重排为 F8 → F4 → F5 → 收口；明确不做自动投递、提词、简历模板、数字人、商业化；信息架构一级导航改为「求职档案 / 面试复盘 / 跨场洞察 / Offer 决策」。
- 原因：用户指出与开源/商业产品同质化严重，Agent 已能完成单次复盘；真实痛点是 15-30 场真实面试的跨场归因与改进闭环，且每场录入必须 ≤5 分钟，不能增加负担。
- 验证：需求文档版本、迭代记录、功能优先级、数据模型、里程碑、风险与 Out of Scope 已交叉核对；本次只改需求，未改代码与契约。
- 下一步 / 断点：按 v1.11 先改契约（applications / interview-events / reviews / improvement-actions / insights），再进入 M3-a；命名等定位确认后按 GitHub / 搜索引擎 / 应用商店 / 商标 / 域名五项检查。

### 15:41 契约 1.9.0 定义 M3 采集与档案骨架
- 改动：`contracts/openapi.yaml` 升至 **1.9.0**，新增 22 个 M3 1.9.0 端点并全部标记 `x-implemented: false`：投递档案 5（列表 / 创建 / 更新 / 删除 / 时间线）、真实面试事件 4、复盘 8（列表 / 创建 / 更新 / 删除 / 状态 / 重试分析 / 确认分析 / 详情）、改进动作 4、复盘词表 1。新增 `JobApplicationStatus` / `InterviewRoundType` / `InterviewFormat` / `InterviewResult` / `ReviewInputType` / `ReviewStageStatus` / `ReviewStatus` / `ImprovementActionStatus` 等枚举，以及投递、面试事件、复盘详情/分析/词表、改进行动、时间线等 schema；补充错误码 40915~40917。
- 原因：用户选择两阶段契约，先定义采集与档案骨架，洞察（F5）待真实数据形状稳定后进 1.10.0，避免过早固化聚合口径。
- 验证：`redocly lint` **0 error / 0 warning**；`ContractCoverageTest` 端点总数 64→86 并通过（新端点均为 pending，已实现集合仍 64）；`ContractSchemaTest` 通过；前端契约类型重新生成 + 生产构建通过。
- 下一步 / 断点：按 M3-a 实现 F8 投递档案（`applications` 表 + 5 端点），完成后逐路径删除 `x-implemented: false`。

### 16:23 档案链路规则确认（需求 v1.12）
- 改动：需求文档升级 v1.12，明确三条规则——(1) Offer 增加可空 `applicationId`，关联具体投递；(2) 被投递引用的简历 / 岗位禁止删除（409），删除投递时若有关联面试事件也返回 409；(3) 投递状态向前同步岗位状态（SAVED → APPLIED → INTERVIEWING → OFFERED/REJECTED，只升不降，WITHDRAWN 不改岗位状态）。同步更新 F6 / F8 / 数据模型 / README / PROGRESS。
- 原因：现有 `offers` 只有 companyId/jobPositionId，同一岗位多次投递时 Offer 归属会歧义；硬删简历/岗位会断掉归因链；岗位状态与投递状态如果完全独立，会让用户重复维护。
- 验证：需求文档版本与迭代记录、F6/F8、数据模型 `offers.application_id`、README/PROGRESS 已交叉核对；本次只改需求与记录，未改代码/契约。
- 下一步 / 断点：M3-a 第一件事把这三条写进契约 1.9.0（Offer 的 applicationId、现有删除端点的 409、岗位状态同步说明），并与 `applications` 建表 + `offers.application_id` 同批实现，避免中间态。

### 16:36 JD 截图导入需求确认（v1.13）
- 改动：需求文档升级 v1.13，F2 新增截图导入——最多 5 张 PNG/JPEG/WebP、单张 ≤5MB；视觉模型先提取 JD 原文，再复用现有结构化解析；原图不落库，只保存提取文本；当前模型不支持视觉时明确提示“切换视觉模型或粘贴文本”，不引入本地 OCR。同步更新数据模型 `job_positions.source_type（TEXT / FILE / IMAGE）`、README、PROGRESS。
- 原因：招聘软件/网站 JD 多以截图形式存在，粘贴文本摩擦大；截图导入符合「≤5 分钟」原则。只存文本可降低磁盘与隐私风险；视觉兜底方案先探测、不引依赖。
- 验证：需求版本与迭代记录、F2 FR2.1/FR2.8/FR2.9、数据模型已交叉核对；本次只改需求与记录，未改代码/契约。
- 下一步 / 断点：实现时契约升至 1.9.1，`POST /api/job-positions` 增加 `images`、`sourceType` 增加 `IMAGE`；与 M3-a 一起排期，先探测当前模型是否支持图片。

### 16:45 视觉能力探测通过
- 探测对象：全局默认 `deepseek / deepseek-flash`（`https://api.deepseek.com`）。
- 方法：生成一张含「月薪 30000 元，双休」的 PNG，调用 `POST /api/offers/extract`（图片走全局默认视觉消息）；`llm_call_logs` 记录 `scene=OFFER_EXTRACT`、`provider=deepseek`、`model=deepseek-flash`、775 tokens、8704ms、success。
- 结果：HTTP 200，正确提取 `monthlySalary=30000`、`twoDayWeekend=true`。结论：当前默认模型支持图片输入，JD 截图导入可直接复用全局默认，无需新增视觉 Provider。
- 下一步 / 断点：实现契约 1.9.1 + 后端图片导入 + 前端截图 Tab。

### 17:20 JD 截图导入实现完成（契约 1.9.1）
- 改动：契约升至 **1.9.1**（`POST /api/job-positions` 增加 `images`、`sourceType` 增加 `IMAGE`）；新增 Flyway `V6__job_position_source_image.sql` 重建来源 CHECK；`application.yml` multipart 总上限 6MB→30MB；后端 `JobPositionController`/`JobPositionService` 支持 `images` 三选一，校验 5 张/5MB/PNG·JPEG·WebP，长截图按 3000px 切片保留 300px 重叠（JDK 无法解码时整图送入），视觉 OCR 按行合并去重、原图不落库、`sourceType=IMAGE`、失败 400 提示切换视觉模型或粘贴文本，`JD_OCR` 埋点；新增 `job-position-ocr-system.st` / `job-position-ocr-user.st`；前端 `api/jobs.ts` 支持多图 FormData，`JobListView` 新增「截图」Tab（多选、缩略图、移除、失败保留）。
- 原因：JD 多为招聘软件截图，按 v1.13 规则补齐截图导入；同步契约、迁移、后端、前端，避免中间态。
- 验证：`redocly lint` 0 error / 0 warning；`JobPositionServiceTest` 14 tests、`ContractCoverageTest` / `ContractSchemaTest` 通过；Vitest 19、前端生产构建通过；真实接口用本地渲染 JD 截图导入成功（HTTP 200、`sourceType=IMAGE`、`storage_key`/`file_name` 为空、OCR 原文准确、结构化出「高级 Java 后端工程师 / 字节跳动 / 上海 / 20-35K」；`llm_call_logs` 记 `JD_OCR` 1122 tokens / 2654ms），验证岗位已通过 DELETE 接口清理。
- 下一步 / 断点：M3-a（F8 投递档案）：契约补 Offer `applicationId` 与删除 409，Flyway `applications` + `offers.application_id`，实现 5 端点与状态向前同步、删除守卫。

### 17:40 M3-a 投递档案实现完成（契约 1.9.2）
- 改动：契约升至 **1.9.2**（Offer / CreateOfferRequest / UpdateOfferRequest 加 `applicationId`；简历与岗位删除补 409/40905；移除投递档案路径 `x-implemented: false`，已实现 69/86）；Flyway `V7__applications.sql`（`applications` + 3 索引 + `offers.application_id ON DELETE SET NULL`）；后端新增 `modules/application`（entity / mapper / 5 端点 controller / service）与 `@MapperScan`，状态向前同步岗位、WITHDRAWN 不改、简历/岗位被引用 40905、Offer 校验并映射 applicationId；前端新增 `/applications` 页面与导航，Offer 表单支持「关联投递（可选）」。
- 原因：v1.12 链路规则要求投递作为归因链起点，Offer 必须能定位到具体投递；删除保护避免断链。
- 验证：`redocly lint` 0/0；`JobApplicationServiceTest` 6、契约与全量后端测试通过（行覆盖 88.43%，JaCoCo 门禁通过）；Vitest 19、前端构建通过；真实接口验证：创建投递（APPLIED）→ 岗位同步 APPLIED → 改 OFFERED → 岗位同步 OFFERED → 降回 APPLIED 岗位仍 OFFERED → 创建关联 Offer → 时间线返回 Offer → 删除岗位 409 → 删除投递后 Offer `application_id` 置空 → 清理 Offer/岗位均 200；Flyway V7 在验收库执行成功。
- 下一步 / 断点：M3-b 真实面试复盘——补 `interview_events` / `review_*` / `improvement_actions` 迁移与 ASR 适配层，实现 17 个待实现端点。

### 17:56 M3-b B1 真实面试轮次实现完成（契约 1.9.3）
- 改动：契约升至 **1.9.3**，移除 `/api/interview-events`（GET/POST）与 `/api/interview-events/{eventId}`（PATCH/DELETE）的 `x-implemented: false`（已实现 73/86，剩 13 个复盘/改进行动端点）；新增 Flyway `V8__m3_reviews.sql`，一次建立 `interview_events` / `review_sessions` / `review_analyses` / `improvement_actions` 四表（轮次唯一、状态 CHECK、复盘唯一、级联删除分析、动作来源置空、索引）；后端新增 `modules/review`——`InterviewEvent` / `ReviewSession` 实体与 mapper、`InterviewEventController` 4 端点、`InterviewEventService`（applicationId 优先；仅给岗位时复用最近投递，无投递则自动创建为 INTERVIEWING 并向前同步岗位；重复轮次 40917；存在复盘禁止删除 40905；批量补公司/岗位/复盘状态）；`JobApplicationService` 注入事件服务：删除投递前检查面试事件、列表补 `interviewCount`/`latestRoundNo`、时间线按轮次升序返回事件；`ErrorCode` 新增 40917；`@MapperScan` 增加 review.mapper；契约/字段对齐测试与 `JobApplicationServiceTest` 同步。
- 原因：M3-b 按 B1→B5 顺序推进；面试事件是复盘与跨场归因的锚点，必须先有稳定归因链（applications → interview_events），后续复盘/行动/洞察才有挂载点。
- 验证：`redocly lint` **0 error / 0 warning**；新增 `InterviewEventServiceTest` 8 tests，`JobApplicationServiceTest` 7 tests，契约双测试通过；后端全量 **151 tests 全绿、JaCoCo 门禁通过**；真实接口验证（隔离库 5433，Flyway V8 自动执行成功）：「建临时公司 → JD 文本导入并异步解析到公司 3/岗位 5 → 建投递 2 → 仅给岗位建事件自动复用投递 2 → 列表/投递统计(1/1)/时间线均正确 → 重复轮次 409/40917 → PATCH 全字段更新成功 → 删投递 409/40905 → 删事件/投递/岗位/公司均 200 → 库内临时数据清零」；只读查询确认 `flyway_schema_history` version=8 success、`interview_events`/`job_positions`/`companies` 临时记录为 0。
- 下一步 / 断点：B2 复盘会话（文本/笔记）8 端点 + 分析 + 词表 + 改进动作 4 端点；B2 完成后解锁契约剩余 13 个路径。

### 18:08 M3-b B2 复盘分析 / 词表 / 改进闭环实现完成（契约 1.9.4）
- 改动：契约升至 **1.9.4**，移除复盘 8、改进动作 4、词表 1 的 `x-implemented: false`（**86 端点全部实现**）；后端新增 `ReviewAnalysis` / `ImprovementAction` 实体与 mapper、`ReviewSessionController`（列表 / 创建 / 详情 / 修正 / 删除 / 状态 / 重跑 / 确认）、`ImprovementActionController`（列表 / 新增 / 更新 / 删除）、`ReviewTaxonomyController`；`ReviewSessionService` 实现文本/笔记创建（音频当前明确提示 B3 启用，不产生卡死任务）、idempotencyKey、同一轮次唯一（40917）、自动/手动异步分析（PROCESSING / COMPLETED / FAILED + ≤3 次重试 + `REVIEW_ANALYZE` 埋点 + Prompt `review-analyze-v1` / rubric / 词表版本 + 重跑覆盖并重置确认）、确认时按草稿创建或复用行动（不删除用户已有行动）；新增外置 Prompt `review-analyze-*.st`、受控词表 `ReviewTaxonomyCatalog`（14 知识点 / 8 失分类型 / 6 维度）；投递时间线接入复盘与行动。
- 原因：F4 的核心链路是「低摩擦录入 → AI 结构化 → 确认 → 改进行动」；先完成文本/笔记路径可在不引入 ASR 依赖的前提下打通完整闭环，B3 只补音频输入层，不返工业务逻辑。
- 验证：`redocly lint` **0 error / 0 warning**；`ReviewSessionServiceTest` 10 + `ImprovementActionServiceTest` 5 + 契约双测试通过；后端全量 **166 tests 全绿、JaCoCo 门禁通过**；真实接口验证：「建临时公司/JD → 建复盘（仅给岗位，自动创建投递 3 与事件 2）→ 自动分析 READY（6 题 + 9 知识点 + 4 失分类型 + 3 行动，`REVIEW_ANALYZE` 成功）→ PUT 确认生成 2 条行动 → 行动 PATCH 置 VERIFIED + 证据 → 投递时间线含事件/复盘/行动 → 重跑分析（confirm 重置 false，行动状态保留 TODO/VERIFIED）→ 存在复盘时删事件 409/40905 → 删复盘后行动与 `review_analyses` 级联清零 → 删事件/投递/岗位/公司均 200 → 库内临时数据清零」。
- 下一步 / 断点：B3 音频上传（mp3/wav/m4a ≤100MB）+ `AsrClient` OpenAI 兼容适配 + `FileStorage` 流式写入 + 独立 ASR 线程池 + 转写状态机与重试；未配置 ASR 环境变量时返回明确提示并允许改用文本。

### 18:17 M3-b B3 音频转写实现完成（契约 1.9.5）
- 改动：契约升至 **1.9.5**，新增 `POST /api/review-sessions/{reviewSessionId}/transcribe`（87 端点全部实现）；新增 `infrastructure/asr`——`AsrClient` 接口、`OpenAiCompatibleAsrClient`（`POST {baseUrl}/audio/transcriptions`，multipart、Bearer、JSON `text` 解析）、`AsrProperties`（`ASR_BASE_URL` / `ASR_API_KEY` / `ASR_MODEL` / `ASR_LANGUAGE` / `ASR_TIMEOUT_SECONDS`）；`AsyncConfig` 新增独立 `asrTaskExecutor`；multipart 上限 100MB / 120MB；`ReviewSessionService` 支持 mp3 / wav / m4a ≤100MB、`reviews/{uuid}.{ext}` 落盘、ASR 状态机（PENDING → PROCESSING → COMPLETED / FAILED，≤3 次重试、错误可读）、转写成功后自动分析、失败保留音频可 `/transcribe` 重试、删除复盘同时删磁盘音频；未配置 ASR 时在入库前返回 400，不产生任务与文件。
- 原因：FR4.1 / FR4.8 要求音频优先录入且失败可手动重试；ASR 是长任务，必须与简历 / JD / 匹配的异步队列隔离，避免一段长录音阻塞其它任务。
- 验证：`redocly lint` **0 error / 0 warning**；`ReviewSessionServiceTest` 15 + `OpenAiCompatibleAsrClientTest` 3 + 契约双测试通过；后端全量 **174 tests 全绿、JaCoCo 门禁通过**；真实链路（本地假 ASR 3001 + 真后端 + 真 LLM）：「未配置 ASR → 400 明确提示；配置后上传音频 → TRANSCRIBING → 假 ASR 转写 → 真 LLM 分析 → READY（转写文本正确、2 题分析）；假 ASR 故障 → FAILED / retry=3 / 错误含上游信息；恢复后 `/transcribe` → READY；删除两条复盘后磁盘音频目录清零、事件/投递/岗位/公司全部清理、库内临时数据为 0」。
- 下一步 / 断点：B4 前端复盘列表 / 详情 / 改进看板（含音频上传、状态轮询、分析确认与行动状态流转）+ 构建与 Vitest/E2E。

### 19:55 M3-b B4 前端复盘页面实现完成
- 改动：新增 `api/reviews.ts`（复盘 / 转写 / 分析 / 改进行动 / 词表全部接口，类型由契约生成）与纯函数 `utils/review.ts`（枚举文案、状态色调、`isProcessing`、`parseTagList`、`buildAnalysisPayload`）；`api/http.ts` 支持 FormData（不再强制 JSON 头）并新增 `postForm`；新增页面 `ReviewListView`（投递选择、轮次自动 +1、录音 / 文本 / 笔记三种输入、处理中 3 秒轮询、重试转写 / 重新分析 / 二次确认删除、失败保留输入）、`ReviewDetailView`（转写修正、轻量状态轮询、分析问题 / 标签 / 总结展示、总结与行动修正、确认生成行动、来源行动列表）、`ImprovementBoardView`（状态筛选、状态流转、验证证据、新增 / 删除）；路由新增 `/reviews`、`/reviews/:id`、`/improvements`；一级导航加入「面试复盘 / 改进看板」（求职洞察保留 M3 占位）；投递时间线展示复盘条目与状态并可跳转；补充 Offer E2E 缺失的 `job-applications` mock，保持「接口全 mock」自包含。
- 原因：B1~B3 后端能力已就绪，B4 必须让用户能完成「录音/文本/笔记 → 分析 → 确认行动 → 看板推进」的完整闭环，否则 M3-b 无法验收。
- 验证：`npm run build`（含契约类型重新生成 + `vue-tsc`）通过；Vitest **23 tests 全过**（新增 `utils/review.spec.ts` 4 个：标签清洗、状态判定、确认 payload 重编号/去空/行动 ≤3、文案）；Playwright **14 E2E 全过**（新增 `e2e/reviews.spec.ts` 3 个：列表文本创建与删除、详情修正并确认行动、看板状态流转 / 证据 / 新增；既有 11 个回归；沙箱对 Edge 系统缓存写入报 restricted 导致退出码 1，测试结果为 14 passed）。
- 下一步 / 断点：B5 全量测试（后端 JaCoCo + 契约 lint + 前端 build / Vitest / E2E）+ 真实接口回归 + README / PROGRESS 收口 + commit push；随后 M3-c 跨场归因（契约 1.10.0）。

### 20:02 M3-b B5 全量回归与收口完成（M3-b 完成）
- 改动：无新增业务代码；完成全量回归、真实接口冒烟与文档收口。
- 原因：B4 完成后按约定执行 B5，确认 B1~B4 在真实链路与全量测试下无回归，才能进入 M3-c。
- 验证：`redocly lint contracts/openapi.yaml` **0 error / 0 warning**（1.9.5，87 端点）；后端 `mvn test` **174 tests 全绿、JaCoCo 全部门禁通过**（JDK 21）；前端 `npm run build`（含契约类型生成 + `vue-tsc`）通过、Vitest **23 tests 全过**、Playwright **14 E2E 全过**（沙箱对 Edge 系统缓存写入报 restricted 导致退出码 1，测试结果为 14 passed）。真实接口冒烟（验收库 5433 + 真后端 + 全局默认 `deepseek/deepseek-flash`）：「建公司 → JD 文本导入异步解析 COMPLETED → 建投递 APPLIED → 建文本复盘 ANALYZING → 轮询 READY（2 题 / 3 知识点 / 3 行动，模型 deepseek-flash）→ PUT 确认 → 生成行动 TODO → PATCH VERIFIED + 证据 → 投递时间线含 1 事件 / 1 复盘 / 1 行动 → 删除复盘 / 事件 / 投递 / 岗位 / 公司全部 200」；只读 SELECT（带 WHERE）复核临时 ID 在 companies / job_positions / applications / review_sessions / review_analyses / improvement_actions **全部 0 行**；后端进程已停止。
- 下一步 / 断点：M3-c 跨场归因与改进闭环——契约 1.10.0（知识点 × 结果、失分类型频次、轮次通过率、公司 / 岗位画像、高频问题、转化漏斗、改进验证率，N<5 样本不足保护）+ 聚合实现 + 前端洞察页；随后 M3-d 收口（命名五项检查需用户最终确认、LICENSE、公开仓库与演示准备）。

### 20:21 契约 1.10.0 定义跨场洞察（待实现）
- 改动：`contracts/openapi.yaml` 升至 **1.10.0**，新增 `GET /api/insights`（路径级 `x-implemented: false`）与 17 个 schema（Sample / Summary / TopicOutcome / TagCount / RoundPassRate / CompanyProfile / Question / Funnel / ResumeFunnel / StaleAction / Improvements / LossTagDelta / Trend / Overview + Result）；参数 `days`（7~365，默认 90）/ `companyId` / `jobPositionId`；聚合口径明确「只使用已确认复盘、N<5 标记 sufficient=false、比例为 0 分母时 null、趋势为当前窗口 vs 前一个等长窗口、纯本地聚合不调用 LLM」；`ContractCoverageTest` 总数 87→88（新增端点仍 pending）。
- 原因：F5 是 M3 核心；先冻结聚合字段与样本保护口径，避免后端实现后反复改契约，也便于前端类型一次生成。
- 验证：`redocly lint` **0 error / 0 warning**；`ContractCoverageTest` / `ContractSchemaTest` 通过（88 端点，1 个待实现，实际已实现 87）。
- 下一步 / 断点：实现 `modules/insights`（mapper 原生 JSONB 聚合 + service 计算比例/趋势/本地总结 + controller），移除 `x-implemented: false` 后补 DTO 字段对齐测试。

### 20:32 M3-c 后端跨场洞察聚合实现完成（契约 88/88）
- 改动：新增 `modules/insights`——`InsightsController`（`GET /api/insights`）、`InsightsService`（比例、样本保护、趋势、本地规则总结）、`InsightsMapper`（PostgreSQL 原生聚合：JSONB `topics` / `lossTags` / `questions` unnest、轮次通过率、公司画像与 Top3 标签、投递 / 简历漏斗、改进统计）、`InsightsRows`、`InsightDtos`（14 个 record，字段与契约一致）；移除契约 `/api/insights` 的 `x-implemented: false`（**88/88 全部实现**），补 `verificationRate = verified/(total-dropped)` 说明；`ContractCoverageTest` 纳入 `InsightsController`，`ContractSchemaTest` 纳入 14 个洞察 DTO；`@MapperScan` 增加 insights mapper。
- 原因：F5 需要跨场规律与样本保护；SQL 侧完成 JSONB 展开与分组，Java 侧只做比例/标签/总结，避免把全量 JSON 拉回内存；N<5 时只给原始统计，防止过度解读。
- 验证：`redocly lint` **0 error / 0 warning**；契约双测试通过；`InsightsServiceTest` 4 tests（比例、样本不足、趋势差值、公司 / 简历 / 陈旧行动映射、参数校验）；后端全量 **178 tests 全绿、JaCoCo 门禁通过**；真实接口冒烟（验收库 5433 + 真后端 + 真 LLM）：空库返回全 0 与 `sufficient=false`；创建 1 条已确认复盘后返回 `topics=4 / lossTags=3 / rounds=1 / companies=1 / questions=2 / funnel.applications=1 / resumeRows=1 / actions=1`，总结正确提示样本不足；删除后只读 SELECT（带 WHERE）复核 5 张表临时数据均为 0。
- 下一步 / 断点：前端洞察页 `/insights`（窗口 / 公司筛选、样本不足提示、知识点失败率、失分频次、轮次通过率、公司画像、高频问题、漏斗、改进验证、趋势）+ Vitest / E2E；随后 M3-c 收口与 M3-d。




