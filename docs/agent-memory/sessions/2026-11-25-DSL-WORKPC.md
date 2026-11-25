# 2026-11-25 DSL-WORKPC

## 目标

修复复盘详情页两处显示问题：转写文本框过窄；改进行动编辑区字段无标签、层级混乱。

## 改动（文件 → 说明）

- `frontend/src/views/ReviewDetailView.vue` → 「转写与面试上下文」textarea 由浏览器默认宽度改为 `width:100%` + `box-sizing:border-box` + 行高 1.6；改进行动编辑行增加「行动 N / 行动内容 / 分类 / 依据（原因）」标签，分类固定 180px、依据自适应，移除按钮移到行头右侧；行动内容仍用 textarea，分类 / 依据用单行 input；「由本复盘生成的改进行动」列表改为墨线分隔，分类改用 `.tag`。

## 决策（为什么）

- 不把所有多行内容改成纯文本：转写、总结、行动内容本身就是用户可修正的字段，textarea 是正确控件；问题在缺少宽度与标签，而不是控件选型。
- 分类、依据是短元数据，用单行 input 更贴语义；通过 overline 标签说明用途，消除「一排无标签输入框」的混乱感。
- 「移除」放到行动行头右侧，与内容 / 元数据分开，降低误触并保持填写顺序。

## 未完成 / 下一步

无；等待用户确认视觉效果。

## 阻塞

无。

## 实时记录

### 09:48 事件
- 改动：按上述方案调整 ReviewDetailView 的转写区与改进行动编辑区排版。
- 原因：用户反馈转写文本框被压窄、改进行动区域混乱，并建议文本框只用于可修改内容。
- 验证：`npm run build`（含 `vue-tsc`）通过；`npx playwright test e2e/reviews.spec.ts` **3 passed**；Edge headless 截图核对复盘详情顶部与行动编辑区，转写框已通栏、行动字段带标签。
- 下一步 / 断点：提交推送；无剩余改动。

### 10:45 事件
- 改动：按用户要求关闭前端与后端服务；前端 5173 后台任务已停止，后端 8080 的 java 进程（PID 42164）已停止。
- 原因：用户完成功能验证，不再需要运行服务。
- 验证：端口 5173 / 5174 / 8080 均无监听；验收库容器 `jobpilot-verify-pg`（127.0.0.1:5433）仍在运行，不在本次关闭范围。
- 下一步 / 断点：如需释放资源可再停 5433 容器；代码无新增改动。

### 10:53 事件
- 改动：按用户确认停止验收库容器 `jobpilot-verify-pg`（`docker stop`）。
- 原因：功能验证完成，释放本机资源。
- 验证：容器状态 `Exited (0)`；5433 端口已释放，5173 / 5174 / 8080 此前已释放。
- 下一步 / 断点：环境全部关闭；无代码改动。

### 11:32 事件
- 改动：公开仓库材料收口。README 新增「项目背景 / 核心能力 / 设计原则 / 授权与使用」，修正克隆地址（afterround）与当前进度；新增 `docs/5分钟演示脚本.md`；脱敏 5 处（契约示例地址 2 处、会话内网地址 2 处、内部服务商名 1 处、本机用户名路径 1 处）；`docker-compose.yml` 默认库密码改为 `change-me`；PROGRESS 更新状态与下一步。
- 原因：用户要求完成公开仓库前的脱敏、README 完善与 M3-d 5 分钟演示材料。
- 验证：`redocly lint` 0 error / 0 warning；grep 复查无内网地址、内部服务商名与本机用户名残留；README 与演示文档互链存在。
- 下一步 / 断点：改动待提交推送；仓库仍为私有，等待用户确认是否公开。

### 15:10 事件
- 改动：README 重构为公开版（582 → 225 行）：新增 4 张真实截图、「为什么做」、核心能力、技术栈、架构、快速开始、测试与 CI、文档导航；原有接口清单、完整配置、多设备部署、前端实现与协作流程完整迁入 `docs/开发手册.md`（453 行）；截图位于 `docs/screenshots/`（简历报告 / 复盘详情 / 洞察 / Offer 对比，共约 360KB）。
- 原因：原 README 混合了公开介绍与内部开发手册，不适合作为公开仓库首页。
- 验证：README 与手册标题结构核对通过，图片路径存在且逐一目视核对；临时启动的后端 8080、前端 5173 与验收库容器均已停止，端口释放。
- 下一步 / 断点：提交推送；仓库仍为私有。

### 15:19 事件
- 改动：按用户要求删除 README 末尾「合规说明」整节，并同步删除 `docs/开发手册.md` 末尾同一句；`AGENTS.md` 内部约束与需求文档中的相关表述保留未动。
- 原因：用户明确要求去掉该句。
- 验证：grep 确认 README 与开发手册已无 `reference/interview-guide` / `AGPL` 表述。
- 下一步 / 断点：提交推送；需求文档仍有 3 处相关表述（第 491、978、1010 行），是否一并清理待用户决定。

### 15:30 事件
- 改动：按用户决定（LICENSE 保持不加、会话记录保留公开）将 GitHub 仓库 `Stitch626-China/afterround` 从私有切换为 **PUBLIC**；开启 Secret Scanning、Push Protection、Dependabot Alerts 与 Security Updates；添加 topics（job-hunting / interview / spring-boot / vue3 / llm / openapi / java）。
- 原因：公开仓库，便于他人查看项目并对 AI 方法论提建议。
- 验证：无痕访问仓库页 200、raw README 200、中文开发手册 raw 200、截图 raw 200；GitHub API 返回 `private=false` / `visibility=public`；安全配置查询显示 secret_scanning / push_protection / dependabot_security_updates 均为 enabled。
- 下一步 / 断点：观察首批访问与 Issue 反馈；LICENSE 保持不加，README 已声明保留所有权利。

### 16:18 事件
- 改动：按用户要求停止本机 8080 后端（`mvn spring-boot:run`），关闭 5 个遗留 Dependabot PR（#2 Tika / #3 faker+redocly / #4 vitest-mocker / #5 tinypool+vitest / #6 vitest）；新增 `scripts/seed-demo.mjs` 演示种子脚本；README「快速开始」新增「可选：导入演示数据」，`docs/5分钟演示脚本.md` 同步脚本入口与匹配分数（80）。
- 原因：遗留 PR 与「个人自用、暂不升级依赖」的决定冲突；公开仓库需要可一键复现的演示数据，原仓库只有演示文档、无种子脚本。
- 验证：8080 已释放、open PR 为 0；临时空库（postgres:16-alpine 5434 + Flyway + 真模型 `deepseek/deepseek-flash`）首次运行成功（简历 83 / 匹配 80 / 5 场已确认复盘 / 15 条行动 / 1 Offer / 洞察样本 2·5·5·1 sufficient），二次运行全部复用无重复；只读 SELECT 带 `WHERE user_id=1` 计数 2/2/1/1/2/5/5/5/15/1，与演示清单一致。
- 下一步 / 断点：临时验证容器 `afterround-seedtest-pg`（5434）与演示库容器 `jobpilot-verify-pg`（5433）因清理命令被跳过仍在运行，待用户确认后清理；本次改动待 commit + push。

### 16:30 事件
- 改动：用户确认后清理验证环境：删除临时验证容器 `afterround-seedtest-pg`，停止演示库容器 `jobpilot-verify-pg`；本地临时 SQL 与测试存储目录已删除。
- 原因：验证完成，恢复验证前状态并释放本机资源。
- 验证：`afterround-seedtest-pg` 已不存在，`jobpilot-verify-pg` 为 `Exited (0)`；5433 / 5434 / 8080 / 8081 全部释放。
- 下一步 / 断点：本次文档与种子脚本已推送（`2c4dafc`），CI 三 job 全绿；无未完成项。

### 16:37 事件
- 改动：首次开启 GitHub `main` 分支保护：禁止强制推送与删除；要求 CI 三个检查（后端测试与覆盖率门禁 / 前端构建、单测与 E2E / 契约校验）通过，`strict=true` 要求分支最新；`enforce_admins=true` 管理员同样受限；未启用「必须走 PR」。
- 原因：用户要求保护 main 分支，防止误强推/误删并让 CI 成为门槛。
- 验证：GitHub API PUT 成功并回读；但随后普通 `git push` 被 GitHub 拒绝：`GH006 Protected branch update failed ... 3 of 3 required status checks are expected`。
- 下一步 / 断点：该配置会破坏现有直接推送工作流，需调整。

### 16:40 事件
- 改动：移除 `required_status_checks`，保留 `allow_force_pushes=false`、`allow_deletions=false`、`enforce_admins=true`；未启用「必须 PR」。
- 原因：GitHub 在 `enforce_admins=true` 时会把 required status checks 也作为直接推送的前置条件；保留 CI 门槛就必须改走 PR 流程，当前优先保持直接推送习惯。
- 验证：API PUT 成功并回读；调整后普通 `git push` 成功（本次提交验证）；强推与删除仍被禁止。
- 下一步 / 断点：如用户希望 CI 成为硬门槛，再改为「分支 + PR + 检查通过后合并」。

### 17:04 事件
- 改动：`.github/workflows/ci.yml` 增加 `workflow_dispatch` 并更新头注释；README「测试与 CI」补充手动触发说明；首次按 PR 流程完成：`ci/workflow-dispatch` 分支 → 提交 `6b41047` / `fd68efb` → PR #7 → Squash 合并 `9b1e15c` → 删除远端与本地分支。
- 原因：用户要求实际演示「feature 分支 + PR + CI + 合并」流程，并希望 CI 可手动触发。
- 验证：PR 两次推送均自动重跑 CI（run 45580524341 / 45581044925）且三 job 全绿；合并后 main push CI（run 45581388702）全绿；`gh workflow run ci.yml --ref main` 手动触发 run 45581740790（workflow_dispatch）三 job 全绿。
- 下一步 / 断点：分支保护仍为「禁强推/禁删、不强制 PR」；如后续要 CI 硬门槛，再启用 Require a pull request + Require status checks。

### 17:32 事件
- 改动：新增本地 `.local/PR流程实操手册.md` 与 `.local/release-notes-v1.0.0.md`（`.local/` 已加入 `.git/info/exclude`，不提交）；在 `95ffa5d` 创建 annotated tag `v1.0.0` 并推送；创建 GitHub Release（`https://github.com/Stitch626-China/afterround/releases/tag/v1.0.0`），附 4 张截图、演示脚本、开发手册、接口契约与演示种子说明。
- 原因：用户要求把 PR 实操整理成本地详细文档，并发布 v1.0.0 tag + GitHub Release。
- 验证：`gh release view` 返回 tag `v1.0.0`、非草稿 / 非预发布、发布时间正常；Release 正文包含截图与演示脚本链接；raw 截图 URL 返回 200；`git ls-remote --tags` 确认远端 tag；工作区干净。
- 下一步 / 断点：无；本次文档提交会触发 main CI，按常规确认全绿即可。
