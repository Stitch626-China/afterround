# 2026-11-20 DSL-WORKPC

## 目标

M2 验收：启动可操作的真实环境（隔离 PostgreSQL + 真后端 + 前端），修复验收过程中发现的问题。

## 改动（文件 → 说明）

- `frontend/src/components/settings/SettingsDrawer.vue`：「打开模型对话」由仅关闭抽屉改为 `openChat()`——关闭抽屉并 `router.push('/chat')`。
- `frontend/e2e/main-flows.spec.ts`：设置抽屉用例补 `/api/chat/sessions` mock 与「点击后 URL 为 `/chat`、抽屉消失」断言。

## 决策（为什么）

- 验收数据库使用 Docker 隔离 PostgreSQL 16（`127.0.0.1:5433`，jobpilot/jobpilot），不触碰本机 5432 的系统 PostgreSQL（项目账号认证失败，且不应改动用户数据）。
- 模型由用户在页面配置（内网网关（地址已脱敏） + Qwen3.8-27B 设为全局默认）；`.env` 的 `LLM_BOOTSTRAP_*` 保持为空，不在仓库或日志写密钥。

## 未完成 / 下一步

1. 用户继续验收 M2 第一批（岗位库 / 行情 / 匹配 / 简历对比 / 场景模型）与第二批（模拟面试 / Offer 决策）。
2. 验收结束后清理临时容器 `jobpilot-verify-pg`，同步 PROGRESS 并 commit + push。

## 阻塞

无。

## 实时记录

### 10:30 M2 验收环境就绪
- 改动：Docker 启动 `jobpilot-verify-pg`（postgres:16-alpine，127.0.0.1:5433）；后端以 `DB_URL` 覆盖指向该库启动（8080）；前端 Vite（5173）。
- 原因：本机 5432 为系统 PostgreSQL，`jobpilot` 账号认证失败；用隔离实例保证验收不影响用户数据。
- 验证：
  - `/actuator/health` = `UP`；Flyway V1→V5 全部 `success`，16 张业务表。
  - 冒烟接口 200：`/api/job-positions`、`/api/interview/directions`（java-backend / ai-agent）、`/api/job-market/insights`、`/api/offers`、`/api/resumes`。
  - 浏览器冒烟：`/interviews` 创建表单与方向选项正常、空态正常；`/offers` 列表/对比 Tab、新增与提取入口正常，均无 JS 报错。
- 下一步 / 断点：用户在设置中配置模型后开始功能验收。

### 11:23 「打开模型对话」无响应修复
- 改动：`SettingsDrawer.vue` 新增 `openChat()`，`ModelSettings` 的 `navigate` 事件改为关闭抽屉并跳转 `/chat`；E2E 补回归断言。
- 原因：原实现 `@navigate="emit('close')"` 只关闭抽屉，从未触发路由跳转，属于 M1 遗留缺陷。
- 验证：`npm run build` 通过（含契约类型生成）；`playwright test e2e/main-flows.spec.ts` **3 passed**；浏览器在 5173 实测 `/resumes → 设置 → 打开模型对话 → /chat`，抽屉关闭、无报错、无控制台异常。
- 下一步 / 断点：继续 M2 验收，发现问题继续修复并追加记录。
