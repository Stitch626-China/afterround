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
