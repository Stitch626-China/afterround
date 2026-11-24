# 2026-11-24 DSL-WORKPC

## 目标

确认产品最终名称，完成展示层与 GitHub 仓库名迁移；不改代码坐标，不引入回归风险。

## 改动（文件 → 说明）

- `README.md` → 标题改为「面后（AfterRound）」，补一句品牌释义，更新需求文档路径与文档索引。
- `frontend/index.html` → 页面标题改为「面后 · 求职全流程 AI 辅助」。
- `frontend/src/App.vue` → 默认页面标题、品牌字、品牌中英文名改为「面 / 面后 / AfterRound」。
- `contracts/openapi.yaml` → `info.title` 与 `info.contact.name` 改为「面后（AfterRound）」；契约版本保持 1.10.0（仅元信息变更，接口形状不变）。
- `docs/superpowers/specs/职点（JobPilot）需求文档.md` → `git mv` 为 `面后（AfterRound）需求文档.md`；标题、版本号（v1.12 → v1.14）、日期更新；迭代脉络新增 v1.14 行；新增 v1.14 产品语言记录；命名沿革补 2026-11-24 更名说明；文档内自引用路径同步。
- `AGENTS.md` → 需求唯一源路径同步。
- `docs/项目全记录与AI协作方法论.md` → 标题、一句话介绍、30 秒介绍、文档索引、时间线（新增 2026-11-24 命名收口行）同步；历史行保留。
- `docs/agent-memory/PROGRESS.md` → 快照产品名、阶段（需求 v1.14）、M3-d 当前状态、下一步与关键决策表更新。
- `backend/pom.xml` → `<description>` 改为「面后（AfterRound）后端服务」（仅元数据）。

## 决策（为什么）

- **只改展示层 + 仓库名，代码坐标保留**：Java 包 `com.jobpilot`、artifactId、数据库与容器名改动面大、无用户收益，且会显著增加回归成本。
- **LICENSE 暂不添加**：用户决定；契约 `info.license` 维持「私有项目（未授权）」。
- **不选职镜 / 面镜 / RoundReady**：均已有同领域产品（职镜 App、面镜 AI / 职面镜小程序、RoundReady 面试记忆项目）。
- **「面后」风险提示**：存在只差一字的「面神」（OfferGod），对外宣传时需明确区分。
- **历史会话记录不改**：旧名保留作为过程存档，符合「只追加不改历史」原则。
- **不动 `V1__init.sql` 注释**：Flyway 已应用迁移的校验和不能变，否则已有库启动会失败。

## 未完成 / 下一步

1. GitHub 仓库改名（`jobpilot` → `afterround`）、更新本地 remote 与仓库描述。
2. 公开仓库前补 5 分钟演示脚本与差异化材料。
3. LICENSE 类型待用户后续确认；中国商标网未查，公开商用前需自查。
4. 域名 `afterround.com` 已被占用；`afterround.app` / `.cn` / `.io` 暂无解析记录，注册前需在注册商确认。

## 阻塞

无技术阻塞。域名 `.com` 不可用属预期风险，不阻塞开发。

## 实时记录

### 09:47 事件
- 改动：完成展示层与需求文档更名（README / 前端标题与品牌区 / 契约 info / 需求文档 v1.14 / AGENTS / 项目全记录 / PROGRESS / pom description）。
- 原因：原名「职点 / JobPilot」与同领域产品重名，用户确认改用「面后（AfterRound）」。
- 验证：`npm run lint:contract` 通过（契约 valid，0 error / 0 warning）；`npm run build` 通过（含 `openapi-typescript` 重新生成类型与 `vue-tsc`）；Grep 复核剩余旧名均为类名、历史记录或不可改的 Flyway 迁移注释。
- 下一步 / 断点：提交推送 → GitHub 仓库改名 → 更新 remote 与描述。

### 09:50 事件
- 改动：提交 `3b4ecc4`（展示层与需求文档 v1.14）已推送；GitHub 仓库 `Stitch626-China/jobpilot` 改名为 `Stitch626-China/afterround`，仓库描述更新为「面后 AfterRound - AI 求职辅助平台：真实面试复盘、跨场归因与改进闭环」；本地 `origin` 同步为新地址。
- 原因：展示层改名后同步仓库名，避免对外地址仍显示旧名。
- 验证：`gh repo view` 返回 `name=afterround`、`visibility=PRIVATE`、描述已更新；`git ls-remote origin refs/heads/main` 返回 `3b4ecc4`，与本地提交一致；旧地址由 GitHub 自动重定向。
- 下一步 / 断点：无；工作区干净。
