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
