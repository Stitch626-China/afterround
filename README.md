# 职点（JobPilot）

AI 求职辅助平台，围绕求职全流程构建闭环：

**简历分析 → 岗位匹配 → 模拟面试 → 真实面试复盘**

一句话定位：让每一次求职动作（改简历、选岗位、练面试、复盘）都有 AI 深度参与和数据沉淀。

当前阶段为**个人自用版**：免登录、单用户、本地 Docker Compose 部署；数据表预留 `user_id`，不堵死后续产品化路径。

## 目录结构

```
interview-main/
├── AGENTS.md               # AI 协作契约（跨设备记忆与开工/收工流程，优先级最高）
├── docs/
│   ├── agent-memory/       # AI 记忆（随 git 同步）
│   │   ├── PROGRESS.md     # 当前进度 / 阻塞 / 下一步 / 关键决策
│   │   └── sessions/       # 每次会话一份记录（只增不改）
│   └── superpowers/
│       └── specs/          # 需求规格文档（唯一需求源）
├── reference/              # 参考项目（AGPL-3.0，仅本地阅读，已 gitignore，禁止入库/复制代码）
└── README.md
```

## 文档索引

| 文档 | 说明 |
|---|---|
| [职点（JobPilot）需求文档](docs/superpowers/specs/职点（JobPilot）需求文档.md) | 唯一需求源，含功能需求、非功能需求、架构、数据模型、接口原则、里程碑 |
| [AGENTS.md](AGENTS.md) | AI 协作契约：开工 pull + 读记忆，收工写记忆 + push |
| [PROGRESS.md](docs/agent-memory/PROGRESS.md) | 当前进度、阻塞、下一步、关键决策 |
| [sessions/](docs/agent-memory/sessions/) | 会话记录：跨日/换设备新建，同日同机追加，不改已有段落 |

> 需求文档维护规则：每次修改必须在文内「变更记录」表格追加一行（版本 / 日期 / 变更内容 / 影响章节），禁止只改正文不记录。

## AI 协作与跨设备同步

项目在多台设备上开发，仓库是唯一事实源，本机 Trae 记忆仅作缓存。

- **开工**：`git pull --rebase --autostash` → 读 `AGENTS.md`、`PROGRESS.md`、最近 1~2 份会话记录，确认任务是否已被另一台设备完成。
- **收工**：更新 `PROGRESS.md` 状态三块 → 写 `sessions/YYYY-MM-DD-<主机名>.md`（跨日/换设备新建，同日同机追加）→ commit → push（失败必须重试，必要时加 `-c http.proxy=http://127.0.0.1:7890`）。
- 会话记录只追加不改已有段落，`PROGRESS.md` 只改状态三块、冲突取并集，避免两台设备同时编辑同一文件产生冲突。
- 同一时间只在一台设备改代码；换设备前先 pull，确认 `git status` 干净。

## 技术底座

| 层 | 选型 | 说明 |
|---|---|---|
| 后端 | Spring Boot 3（Java） | REST + SSE 流式输出 |
| 数据库 | PostgreSQL | 异步任务表含 `status` / `error` / `retry_count` |
| 文件存储 | 本地磁盘 | `FileStorage` 接口隔离，产品化再换 MinIO |
| 模型网关 | 手写 WebClient（OpenAI 兼容） | 不用 Spring AI，便于会话级模型绑定与调用追溯 |
| 前端 | Vue3 单页 | M1 极简，M2 完整 |
| 部署 | Docker Compose | 本地一键启动 |

## 里程碑

- **M1（MVP）**：工程骨架 + 模型网关（多 Provider 配置 / 连通测试 / 全局切换 / SSE）+ 简历上传解析分析 + 岗位方向推荐（轨一）+ 极简单页
- **M2**：完整前端 + JD 导入 / 匹配评分 / 行情统计 + 结构化模拟面试
- **M3**：面试复盘（ASR / 分析 / 报告）+ PDF 导出 + Token 成本统计 + 产品化准备

## 当前进度

需求文档 v1.2 已确认，工程代码尚未开始（M1 待开工）。

## 合规说明

`reference/interview-guide` 为 AGPL-3.0 项目，**只借鉴架构设计思想，所有代码自研**，不复制其中任何代码；该目录已加入 `.gitignore`，不纳入版本管理。
