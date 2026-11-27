# LLM 评测集语料规范

> 版本 v1.3 / 2026-11-27 / 适用目录 `backend/src/test/resources/llm-eval/`
> 状态：评测执行器已实现（`backend/src/test/java/com/jobpilot/eval/`），默认 `mvn test` 排除真实模型调用；本文件仍是建库、标注与执行的唯一依据。

## 0. 快速开始（在另一台主机）

1. `git pull` 拿到最新代码与本规范；
2. 在仓库**外**建原始层目录，放真实转写、真实简历、抓取的 JD（永不入 git）；
3. 在 `backend/src/test/resources/llm-eval/cases/` 下建 case 目录，逐份脱敏；
4. 每个 case 至少写三个文件：`input.txt`、`meta.json`、`expect.json`；
5. 按第 7 节自查隐私、按附录 B 逐项自检；
6. 只提交 `cases/`（必要时含 `.gitignore`），push；
7. 把**一个场景目录**的路径交给 AI 复核；执行器已实现，按第 12 节运行并生成基线。

先做 `review-analyze` 一个场景（8 份），跑通后再做另外两个场景。

## 1. 目的与边界

- **评测对象**：prompt + 结构化治理链路（外置模板、JSON 模式、本地修复、带错误反馈重试、本地兜底校验），不是模型能力排名。
- **评测目标**：改 prompt、换模型、升级网关时，退化能被自动发现，而不是靠人眼抽检。
- **不评测**：ASR / OCR 识别质量、前端 UI、接口性能、数据库状态机。
- **首批范围**：3 个场景共 22 份语料（复盘 8 / JD 8 / 简历 6）。

## 2. 两层存储

| 层 | 位置 | 是否入 git | 内容 |
|---|---|---|---|
| 原始层 | 仓库外，任意私有目录 | **否** | 真实转写、真实简历、抓取 JD、原始音频、标注草稿 |
| 入库层 | `backend/src/test/resources/llm-eval/cases/` | 是 | 脱敏或合成后的 `input.txt` / `meta.json` / `expect.json` |

原始层没有固定路径要求，只要在仓库目录之外即可。推荐结构：

```
<私有根目录>/
├── raw/
│   ├── review/2025-10-companyA-round2.txt        # 原始转写
│   ├── review/2025-10-companyA-round2.meta.md    # 元数据 + 你的手工标注
│   ├── jd/2025-10-java-backend.txt
│   └── resume/version-a.txt
└── notes/
    └── 标注记录.md                                # 真实失分点、标注理由
```

判定规则：

1. 含姓名、公司、学校、联系方式、内部系统名、独特业务指标 → 只能留在原始层；
2. 拿不准能不能公开 → 只留原始层，不入库；
3. 入库层允许改写措辞，但**禁止改变技术事实**（不能把「答不出 GC 参数」改成「答得很好」）。

仓库是公开仓库。如果原始层或临时目录碰巧放在仓库内，以下路径必须加入 `.gitignore`，且提交前 `git status` 必须干净：

```gitignore
corpus-local/
raw/
```

## 3. 目录结构

```
backend/src/test/resources/llm-eval/
├── cases/
│   ├── resume-analysis/
│   │   ├── 001-typical-java-3y/
│   │   │   ├── input.txt
│   │   │   ├── meta.json
│   │   │   └── expect.json
│   │   └── 002-...
│   ├── review-analyze/
│   │   ├── 001-real-tech2-jvm-gap/
│   │   │   ├── input.txt
│   │   │   ├── meta.json
│   │   │   ├── expect.json
│   │   │   ├── jd.txt                  # 可选，关联岗位 JD
│   │   │   ├── company-intro.txt       # 可选，公司介绍
│   │   │   └── resume.txt              # 可选，关联简历
│   │   └── ...
│   └── jd-parse/
│       └── ...
└── baselines/                          # 由评测执行器生成，不手写
    ├── resume-analysis.json
    ├── review-analyze.json
    └── jd-parse.json
```

约定：

- **一个 case 一个目录**，不平铺。一份语料至少包含「输入 + 场景参数 + 断言」三个文件，复盘还可能带背景材料。
- 场景目录名固定为 `resume-analysis` / `review-analyze` / `jd-parse`，与 `llm_call_logs.scene` 的 `RESUME_ANALYSIS` / `REVIEW_ANALYZE` / `JD_PARSE` 一一对应。
- 基线文件由执行器生成，语料作者不手写。

## 4. 分类、命名与 case 之间的关系

分类只有两级：

1. **一级：场景**（上述三个目录）；
2. **二级：三位序号 + 语义标签**，例如 `001-real-tech2-jvm-gap`。

命名规矩：

- 只用小写 ASCII、数字、连字符；
- 序号三位补零，决定执行顺序与报告顺序；
- 语义标签写「来源 + 特征」，如 `real` / `synthetic` / `negative` / `short` / `no-metrics`；
- **禁止**出现公司名、人名、日期、中文文件名。

**case 之间保持独立**：

- 断言只允许依赖本 case 目录内的文件，禁止 `../` 跨 case 引用；
- 一次生产调用需要几份材料，一个 case 就放几份（复盘可带 `jd.txt` / `resume.txt` / `company-intro.txt`）；
- 不同场景的语料不要求是同一个人、同一份简历、同一个岗位；强行配对会牺牲覆盖率；
- 端到端演示链路不属于评测集，用 `scripts/seed-demo.mjs` 维护；
- 未来若加入「岗位匹配」场景，它天然成对：同一个 case 内放 `resume.txt` 与 `jd.txt`，仍不跨 case 引用。

## 5. 单份 case 规范

### 5.1 input.txt（必填）

- 编码 UTF-8（无 BOM），换行 LF（仓库 `.gitattributes` 已统一）；
- 只放**送给模型的正文材料**：简历正文 / JD 原文 / 复盘转写或笔记；
- 复盘保留口语重复、错字、断句混乱等原始特征，这是被评测对象的一部分，不要润色；
- 不写标题、不写「以下是简历」这类说明、不加 Markdown 结构、不写期望答案；
- 建议单份 ≤ 30000 字符；超出时保留完整内容，并在 `meta.json` 标记 `"longInput": true`，评测报告单独统计成本。

### 5.2 meta.json（必填）

**通用字段**

| 字段 | 类型 | 必填 | 取值 / 说明 |
|---|---|---|---|
| `scene` | string | 是 | `resume-analysis` / `review-analyze` / `jd-parse`，必须与所在目录一致 |
| `sourceType` | string | 是 | `real-transcript` / `real-notes` / `community-rewrite` / `synthetic` / `negative` |
| `sanitized` | boolean | 是 | 入库层必须为 `true`；执行器拒绝 `false` 的 case |
| `note` | string | 否 | 一句话说明样本特征，不写隐私 |
| `longInput` | boolean | 否 | 输入超过 30000 字符时置 `true` |

**`review-analyze` 额外字段**（与复盘 prompt 入参一致）

| 字段 | 类型 | 必填 | 取值 |
|---|---|---|---|
| `companyName` | string | 是 | 脱敏占位，如 `[公司A]` |
| `jobTitle` | string | 是 | 岗位名，可保留通用职级 |
| `roundType` | string | 是 | `HR` / `TECH` / `CROSS` / `DIRECTOR` / `FINAL` / `OTHER` |
| `interviewerRole` | string | 否 | 自由文本，如「技术负责人」 |
| `format` | string | 否 | `PHONE` / `VIDEO` / `ONSITE` / `OTHER` |
| `inputType` | string | 是 | `TEXT`（完整转写）/ `NOTES`（笔记 / 面经） |

**字段纪律**：执行器对未知字段直接报错，不静默忽略。写错键名会立刻暴露，避免断言悄悄失效。

`meta.json` 示例（复盘）：

```json
{
  "scene": "review-analyze",
  "sourceType": "real-transcript",
  "sanitized": true,
  "note": "二面，JVM 与系统设计明显失分",
  "companyName": "[公司A]",
  "jobTitle": "Java 后端开发",
  "roundType": "TECH",
  "interviewerRole": "技术负责人",
  "inputType": "TEXT"
}
```

`meta.json` 示例（简历 / JD）：

```json
{
  "scene": "resume-analysis",
  "sourceType": "community-rewrite",
  "sanitized": true,
  "note": "三年经验，项目描述无量化"
}
```

```json
{
  "scene": "jd-parse",
  "sourceType": "synthetic",
  "sanitized": true,
  "note": "边界样本：只有福利，没有职责与要求"
}
```

### 5.3 expect.json（必填）

只给评测器读，绝不拼进 prompt。断言分三层，键名大小写敏感。

**路径语法**：`a.b` 表示对象字段，`a[]` 表示数组全部元素，如 `questions[].score`。

#### L1 硬断言（必须 100% 通过）

| 键 | 类型 | 含义 |
|---|---|---|
| `requiredFields` | string[] | 输出必须存在的字段路径 |
| `scoreRange` | object | 字段路径 → `[最小值, 最大值]`，如 `{"questions[].score": [0, 100]}` |
| `quotesMustComeFromInput` | boolean | **仅简历**：`strengths[].quote` / `weaknesses[].quote` 必须是 `input.txt` 的子串（去空白与引号后比对），抓幻觉引用 |
| `maxItems` | object | 列表路径 → 数量上限，如 `{"actions": 3}` |
| `overallFormula` | string | 仅简历：总分必须等于本地加权重算值，固定 `"30/25/20/15/10"` |

`maxItems` 只能写 prompt 或需求明确约束的数量；prompt 未约束的列表不要加断言，否则会把「模型多写一条」误判为退化。

引用字段取值：

- 简历：`strengths[].quote`、`weaknesses[].quote`，prompt 明确要求原句，按 L1 严格校验；
- 复盘：`questions[].evidence` 只要求「尽量引用原文」，允许转述，**不做逐字断言**；关键事实用 `mustMentionAny` 校验。

#### L2 质量断言（通过率 ≥95%）

| 键 | 类型 | 含义 |
|---|---|---|
| `topicsMustInclude` | string[] | 必须命中的知识点 key（附录 A） |
| `lossTagsMustInclude` | string[] | 必须命中的失分类型 key |
| `mustMentionAny` | string[] | 至少命中一个关键词；匹配范围为输出中**除引用字段**（`quote` / `evidence` / `original`）以外的字符串值，去空白、ASCII 不区分大小写 |
| `mustNotContainTags` | string[] | 禁止出现的词表 key，用于负样本防硬凑 |
| `skillsMin` | integer | JD：`mustHaveSkills` + `niceToHaveSkills` 总数下限 |
| `expectGraceful` | boolean | 允许优雅失败，定义见下 |

**优雅失败**的定义：以下任一即通过——接口返回可读业务错误（4xx/5xx + 明确提示）；或分析成功但明确标注样本信息不足、不产出捏造条目。以下不通过——未处理异常、空字符串、凭空编造技术标签。

`topicsMustInclude` / `lossTagsMustInclude` / `mustNotContainTags` 的匹配范围：复盘取 `topics[].tag` 与 `questions[].topics[]`、`lossTags[].tag` 与 `questions[].lossTags[]` 的并集；其余场景按同名字段。

#### L3 稳定性（同一输入连跑 2 次）

| 键 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `scoreDriftMax` | integer | 5 | 可对齐数值分数两次运行的最大绝对差；题目按 `questionIndex` 对齐，简历按字段名对齐 |
| `tagJaccardMin` | number | 0.7 | 两次运行标签集合（topics ∪ lossTags）的 Jaccard 相似度下限 |

#### 三个场景的最小示例

简历：

```json
{
  "requiredFields": ["technicalDepth", "strengths", "weaknesses", "suggestions"],
  "quotesMustComeFromInput": true,
  "scoreRange": { "technicalDepth": [0, 70] },
  "maxItems": { "careerDirections": 5 },
  "overallFormula": "30/25/20/15/10",
  "mustMentionAny": ["量化", "指标"],
  "scoreDriftMax": 5
}
```

复盘：

```json
{
  "requiredFields": ["questions", "topics", "lossTags", "actions"],
  "topicsMustInclude": ["jvm"],
  "lossTagsMustInclude": ["shallow-depth"],
  "maxItems": { "questions": 20, "actions": 3 },
  "mustMentionAny": ["GC", "垃圾回收"],
  "tagJaccardMin": 0.7,
  "scoreDriftMax": 5
}
```

JD：

```json
{
  "requiredFields": ["title", "mustHaveSkills"],
  "skillsMin": 3,
  "maxItems": { "mustHaveSkills": 30, "niceToHaveSkills": 30 },
  "scoreDriftMax": 5
}
```

负样本（纯 HR 轮、没有技术内容）：

```json
{
  "expectGraceful": true,
  "mustNotContainTags": ["knowledge-gap"]
}
```

负样本只禁止**技术性**失分类型，避免把通用的表达深度判断误判为硬凑；未在 prompt 中约束数量的列表不写 `maxItems`。

### 5.4 可选背景文件（仅 `review-analyze`）

| 文件 | 用途 | 生产截断 |
|---|---|---|
| `jd.txt` | 目标岗位 JD | 8000 字符 |
| `company-intro.txt` | 公司介绍 | 2000 字符 |
| `resume.txt` | 关联简历 | 6000 字符 |

存在时由评测器按生产同款规则注入；不存在则跳过。音频、截图等二进制**只放原始层**。

## 6. 各场景怎么造语料

### 6.1 `resume-analysis`（6 份）

输入是一份完整简历正文，不带标题说明。

| 编号 | 特征 | 断言重点 |
|---|---|---|
| 001~003 | 社区简历改写（后端 / AI / 应届各一） | 引用必须来自原文；方向 ≤5 |
| 004 | 合成缺陷：项目只写「负责 XX 模块开发」，无量化 | `mustMentionAny` 命中「量化 / 指标」 |
| 005 | 合成缺陷：有空窗期或转行背景 | 短板必须提及该事实 |
| 006 | 超短简历（≤200 字） | 不崩溃；`expectGraceful` 可选 |

合成方法：先写人设与缺陷，再让模型生成简历；同一条缺陷至少留 2 份不同写法，避免断言只对一种措辞生效。

### 6.2 `review-analyze`（8 份）

| 编号 | 来源 | 输入形态 | 说明 |
|---|---|---|---|
| 001~004 | 你的 4 场真实转写 | `TEXT` | 先人工标注真实失分点，再写断言 |
| 005~007 | 面经改写 | `NOTES` | 从公开面经取问答要点，改写成第一人称笔记 |
| 008 | 负样本 | `NOTES` | 纯 HR 轮，无技术内容 |

**真实转写标注步骤**（每份约 10~15 分钟）：

1. 通读转写，标出真实失分点与知识盲区；
2. 在 `expect.json` 里把「最迟必须抓到的 1~2 个点」写成 `topicsMustInclude` / `lossTagsMustInclude`；
3. 其余失分点只记录在原始层 `notes/`，不进断言，避免评测过严。

**面经改写规矩**：只取问答要点，句子全部重写；保留问题意图，不保留原文表达；公司名、人名一律替换；改写后读起来应像本人回忆笔记，而不是逐字稿。

**合成（当真实素材不足时）**：

- 人设：年限 + 岗位族 + 目标公司类型 + 轮次；
- 预设缺陷：如「说不清 G1 参数」「系统设计不做容量估算」「缓存一致性只会背结论」「项目优化无量化」；
- 生成要求：800~2000 字，保留口语重复、停顿、被追问后改口；
- 一次生成 10~20 份，人工筛掉不像真人对话的，留 3~4 份；
- `expect.json` 直接引用你埋的缺陷（`topicsMustInclude` / `lossTagsMustInclude`），这是合成语料的最大价值：**先有标准答案，再有样本**。

### 6.3 `jd-parse`（8 份）

| 编号 | 来源 | 特征 |
|---|---|---|
| 001~005 | 招聘软件手抄改写 | 覆盖后端 / AI / 数据 / 客户端 / 测试 |
| 006 | 合成边界 | 只有福利介绍，没有职责与要求 |
| 007 | 合成边界 | 中英混杂、职责与要求混在一段 |
| 008 | 负样本 | 无职责信息，期望优雅失败 |

JD 入库只保留「结构 + 术语」，删除公司名、联系方式、独特业务描述。

## 7. 脱敏与自查

| 类型 | 处理 |
|---|---|
| 人名、面试官称呼 | `[候选人]` / `[面试官]` |
| 公司、学校 | `[公司A]` / `[学校A]` |
| 手机号、邮箱、微信号 | 直接删除 |
| 项目名、内部系统名 | `[项目X]` / `[系统X]` |
| 独特业务指标（如「日均 300 万单」） | 改为量级描述，如「日均百万级」 |
| 可检索的独特技术组合 | 保留技术事实，调整组合顺序或替换同类组件 |

提交前用 PowerShell 搜索入库目录，确认零命中：

```powershell
Get-ChildItem -Recurse -File backend/src/test/resources/llm-eval/cases |
  Select-String -Pattern "真实姓名","公司全名","13800138000","@qq.com"
```

（把示例关键词换成你实际脱敏掉的词。）

## 8. 基线与变更规则

1. **先冻结基线，再改 prompt**：评测执行器实现后，用当前代码跑一遍全部语料，输出存 `baselines/`；
2. 基线记录 `generatedAt`、模型、每个 case 的关键分数、标签集合、耗时、token；代码版本以生成时的 git commit 为准，记录在会话与提交信息中；
3. 执行器启动时校验：**基线中的 case id 集合必须与目录扫描结果一致**，不一致直接报错；
4. 改语料或改基线都算口径变更，必须单独提交并在提交信息里说明原因；
5. 以下任一情况视为退化：L1 由 PASS 变 FAIL；某 case 分数漂移 >10；标签集合 Jaccard <0.7；L2 通过率低于 95%。

基线文件形态（执行器生成，仅供参考）：

```json
{
  "generatedAt": "2026-11-27T10:00:00+08:00",
  "model": "provider/model",
  "cases": {
    "001-real-tech2-jvm-gap": {
      "status": "PASS",
      "scores": { "q1.score": 55, "q2.score": 70, "q3.score": 60 },
      "topics": ["jvm", "system-design"],
      "lossTags": ["shallow-depth"],
      "tokens": 1234,
      "elapsedMs": 3000
    }
  }
}
```

## 9. 建库完整流程

1. `git pull` 拿到本规范；
2. 在仓库外建原始层（第 2 节推荐结构）；
3. 放入 4 份真实转写、元数据、你记得的真实失分点；
4. 只针对 `review-analyze` 建 8 个 case 目录；
5. 每份：脱敏 → 写 `input.txt` → 写 `meta.json` → 人工标注 → 写 `expect.json`；
6. 按第 7 节搜索隐私关键词，按附录 B 逐项自检；
7. 只提交 `cases/`，提交信息建议 `test(eval): 新增 review-analyze 语料 8 份`；
8. push，然后在开发机 `git pull`（如有未提交改动，先处理再拉）；
9. 把 `backend/src/test/resources/llm-eval/cases/review-analyze/` 交给 AI，实现执行器与基线。

## 10. 交付与协作方式

- 不要一次性把 22 份全给 AI：按场景分批，一次 ≤8 份；
- 给路径即可，不必贴正文；
- 需要 AI 帮忙标注时，明确说「读这个目录，补 `expect.json`」；AI 只读入库层；
- 评测执行器实现时复用生产同款 `PromptFactory` 与 `StructuredOutputInvoker`，禁止复制 prompt 文本；
- 默认 `mvn test` 必须排除 `llm-eval` tag，避免无密钥环境与 CI 变红。

## 11. 常见错误

| 错误 | 后果 | 正确做法 |
|---|---|---|
| 把期望答案写进 `input.txt` | 模型照抄，评测失真 | 期望只写 `expect.json` |
| 断言用中文 label（`「JVM 与 GC」`） | 词表过滤后永不命中 | 用 key `jvm` |
| 目录编号用日期或公司名 | 隐私泄露、排序混乱 | 三位序号 + 语义标签 |
| `meta.json` 拼错键名 | 参数缺失、静默失败 | 执行器对未知键报错，写前对照第 5.2 节 |
| 一个 case 里塞多场面试 | 输入噪声、断言无法归因 | 一次调用一个 case |
| 提交原始转写或音频 | 公开仓库泄露隐私 | 原始层永不入 git |
| 语料改写时改变技术事实 | 断言与真实能力脱节 | 只改措辞，不改结论 |
| 手写 `baselines/` | 与代码版本不一致 | 只由执行器生成 |

## 12. 运行评测与生成基线

默认 `mvn test` 通过 surefire 排除 `llm-eval` tag，不调用模型；语料校验（`EvalCorpusTest.realCorpusIsCompliant`）仍在默认测试中执行。

### 12.1 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `EVAL_BASE_URL` | 是 | OpenAI 兼容地址，建议含 `/v1` |
| `EVAL_API_KEY` | 是 | 上游密钥，只放进程环境变量，不写文件 |
| `EVAL_MODEL` | 是 | 被测模型名 |
| `EVAL_MAX_TOKENS` | 否 | 默认 8192（评测值；生产 NFR7 仍为 4096） |
| `EVAL_TIMEOUT_SECONDS` | 否 | 默认 300（生产客户端默认 120s） |
| `EVAL_REPEAT` | 否 | 默认 2，用于 L3 稳定性 |
| `EVAL_WRITE_BASELINE` | 否 | 首次生成基线设为 `true`，必须全量运行 |
| `EVAL_ONLY` | 否 | 逗号分隔 case id 或 `scene/id`，仅调试用；不可与写基线同用 |

长复盘评测放宽 token 与超时，是为了评 prompt 质量而非复现基础设施上限；生产 4096 / 120s 的约束单独评估。

### 12.2 命令

macOS / Linux：

```bash
EVAL_BASE_URL="..." EVAL_API_KEY="..." EVAL_MODEL="..." EVAL_WRITE_BASELINE=true \
mvn -o -f backend/pom.xml -Djacoco.skip=true -Dsurefire.excludedGroups=__none__ -Dgroups=llm-eval test
```

Windows PowerShell：

```powershell
$env:EVAL_BASE_URL="..."; $env:EVAL_API_KEY="..."; $env:EVAL_MODEL="..."
$env:EVAL_WRITE_BASELINE="true"
mvn -o -f backend/pom.xml "-Djacoco.skip=true" "-Dsurefire.excludedGroups=__none__" "-Dgroups=llm-eval" test
```

`-Dsurefire.excludedGroups=__none__` 用于覆盖 pom 中默认的 `llm-eval` 排除；`-Dgroups=llm-eval` 指定只跑评测。

### 12.3 输出与判定

- 报告：`backend/target/llm-eval/report.md` 与 `report.json`（逐 case 的 L1/L2/L3、tokens、耗时、失败项）；
- 基线：仅在 `EVAL_WRITE_BASELINE=true` 且全量运行时写入 `backend/src/test/resources/llm-eval/baselines/{scene}.json`；
- 基线存在且非写入模式时自动对比：case 集合、模型名、分数漂移 >10、标签 Jaccard <0.7 均判失败；分数对齐方式为简历按字段名、复盘按 `questionIndex` + 维度名，数组顺序变化不产生漂移；
- 判定口径：L1 / L3 任一失败即整轮失败；L2 按**断言**通过率聚合，低于 95% 才判失败，未通过的 case 在报告中仍标 FAIL；
- 换模型必须重建基线；负样本的 `expectGraceful` 允许优雅失败，不参与基线分数对比。

### 12.4 成本控制

- 全量 27 份 × 2 次 ≈ 54 次调用；调试 prompt 时用 `EVAL_ONLY` 跑单 case，不要写基线；
- 报告只留断言结果与 token，不保存请求 / 响应全文，避免语料膨胀。

### 12.5 当前基线（2026-11-27，`resume-analysis-v3`）

- 环境：内网 `datangsoft / Qwen3.8-27B`，`EVAL_MAX_TOKENS=8192`、超时 300s、`repeat=1`；
- 结果：27 份全量，L1 全部通过（`resume-analysis/002` 的省略号拼接已由 prompt v3 修复）；L2 断言 45 项、失败 2 项（95.6%，达标），未命中为 `review-analyze/003`（`knowledge-gap`）与 `004`（`no-evidence`），保留为模型波动观察项；
- 分数对齐：简历按字段名，复盘按 `questionIndex` + 维度名写入（如 `q1.score` / `q1.depth`），数组顺序变化不产生漂移；
- 首版基线不引入 xfail 机制：改 prompt、语料或模型后按第 8 节重建，不要手改 `baselines/`。

## 附录 A：枚举与词表

- `scene`：`resume-analysis` / `review-analyze` / `jd-parse`
- `sourceType`：`real-transcript` / `real-notes` / `community-rewrite` / `synthetic` / `negative`
- `roundType`：`HR` / `TECH` / `CROSS` / `DIRECTOR` / `FINAL` / `OTHER`
- `inputType`：`AUDIO` / `TEXT` / `NOTES`（首批只用 `TEXT` / `NOTES`）
- 知识点 `topics`（`taxonomy-v1`）：`java` / `jvm` / `concurrency` / `mysql` / `redis` / `kafka` / `spring` / `distributed` / `system-design` / `algorithm` / `project` / `communication` / `career` / `other`
- 失分类型 `lossTags`（`taxonomy-v1`）：`knowledge-gap` / `shallow-depth` / `unclear-expression` / `no-evidence` / `off-topic` / `time-management` / `pressure` / `other`
- 评分维度：`depth` / `accuracy` / `structure` / `evidence` / `adaptability` / `overall`
- 各场景 AI 输出字段（写 `requiredFields` / `maxItems` 时用）：

| 场景 | 顶层字段 |
|---|---|
| `resume-analysis` | `technicalDepth` / `projectQuality` / `engineeringAbility` / `expressionStructure` / `jobMatchPotential` / `basicInfo` / `strengths` / `weaknesses` / `suggestions` / `careerDirections` |
| `review-analyze` | `questions` / `topics` / `lossTags` / `summary` / `actions`；问题内字段 `questionIndex` / `question` / `answerSummary` / `score` / `dimensions` / `topics` / `lossTags` / `evidence` / `feedback` / `referenceAnswer` |
| `jd-parse` | `title` / `companyName` / `city` / `salaryMin` / `salaryMax` / `experience` / `education` / `mustHaveSkills` / `niceToHaveSkills` / `responsibility` |

词表以 `backend/src/main/java/com/jobpilot/modules/review/taxonomy/ReviewTaxonomyCatalog.java` 为准；词表升级时同步升级本附录。

## 附录 B：单份 case 自检清单

- [ ] 目录名符合「三位序号 + 语义标签」，全 ASCII
- [ ] `input.txt` 为 UTF-8 无 BOM、LF，无标题与期望答案
- [ ] `meta.json` 的 `scene` 与所在目录一致
- [ ] `sanitized` 为 `true`，关键词搜索零命中隐私信息
- [ ] `expect.json` 至少包含一条 L1 硬断言
- [ ] 断言使用词表 **key**，不是中文 label
- [ ] 负样本使用 `expectGraceful`，并写清禁止出现的标签
- [ ] 未把原始音频、截图、真实公司名放入入库层
- [ ] 未出现跨 case 的 `../` 引用
- [ ] 基线存在后，新增 / 删除 case 已同步更新基线 id 集合

## 附录 C：完整示例（`review-analyze/001`）

目录：

```
cases/review-analyze/001-real-tech2-jvm-gap/
├── input.txt
├── meta.json
├── expect.json
└── jd.txt
```

`input.txt`（节选，真实使用时放完整转写）：

```
面试官：先说说你项目里 JVM 调优是怎么做的。
候选人：嗯……我们主要是加内存，然后调了一下 Xmx，具体参数记不太清了。
面试官：那 G1 的 Region 和停顿目标你了解吗？
候选人：这个……我只知道 G1 是分区的，参数没怎么用过。
面试官：如果线上频繁 Full GC，你怎么排查？
候选人：先看日志吧，然后……可能是内存泄漏，加机器应该能顶一下。
```

`meta.json`：

```json
{
  "scene": "review-analyze",
  "sourceType": "real-transcript",
  "sanitized": true,
  "note": "二面，JVM 调优答不出参数与排查路径",
  "companyName": "[公司A]",
  "jobTitle": "Java 后端开发",
  "roundType": "TECH",
  "interviewerRole": "技术负责人",
  "inputType": "TEXT"
}
```

`expect.json`：

```json
{
  "requiredFields": ["questions", "topics", "lossTags", "actions"],
  "topicsMustInclude": ["jvm"],
  "lossTagsMustInclude": ["shallow-depth"],
  "maxItems": { "questions": 20, "actions": 3 },
  "mustMentionAny": ["G1", "Full GC"],
  "scoreDriftMax": 5,
  "tagJaccardMin": 0.7
}
```

`jd.txt`（可选，节选）：

```
岗位职责：负责核心交易系统的服务端开发与性能优化。
任职要求：熟悉 Java、JVM、MySQL、Redis，有高并发经验者优先。
```
