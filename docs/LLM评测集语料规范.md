# LLM 评测集语料规范

> 版本 v1.0 / 2026-11-26 / 适用目录 `backend/src/test/resources/llm-eval/`
> 状态：规范先行，评测执行器尚未实现。本文件是建库、标注与后续实现的唯一依据。

## 1. 目的与边界

- **评测对象**：prompt + 结构化治理链路（外置模板、JSON 模式、本地修复、带错误反馈重试、本地兜底校验），不是模型能力排名。
- **评测目标**：改 prompt、换模型、升级网关时，退化能被自动发现，而不是靠人眼抽检。
- **不评测**：ASR / OCR 识别质量、前端 UI、接口性能。
- **首批范围**：3 个场景共 22 份语料（复盘 8 / JD 8 / 简历 6）。

## 2. 两层存储

| 层 | 位置 | 是否入 git | 内容 |
|---|---|---|---|
| 原始层 | 仓库外，例如 `<语料私有根目录>/` | **否** | 真实转写、真实简历、抓取 JD、原始音频、人工标注草稿 |
| 入库层 | `backend/src/test/resources/llm-eval/cases/` | 是 | 脱敏或合成后的 `input.txt` / `meta.json` / `expect.json` |

判定规则：

1. 含姓名、公司、学校、联系方式、内部系统名、独特业务指标 → 只能留在原始层；
2. 拿不准能不能公开 → 只留原始层，不入库；
3. 入库层允许改写措辞，但**禁止改变技术事实**（不能把「答不出 GC 参数」改成「答得很好」）。

仓库是公开仓库，以下路径必须加入 `.gitignore`，且收工前 `git status` 必须干净：

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
└── baselines/
    ├── resume-analysis.json
    ├── review-analyze.json
    └── jd-parse.json
```

约定：

- **一个 case 一个目录**，不平铺。因为一份语料至少包含「输入 + 场景参数 + 断言」三个文件，复盘还可能带三类背景材料。
- 场景目录名固定为 `resume-analysis` / `review-analyze` / `jd-parse`，与 `llm_call_logs.scene` 的 `RESUME_ANALYSIS` / `REVIEW_ANALYZE` / `JD_PARSE` 一一对应。
- 基线文件在评测执行器实现后生成，文件名与场景目录一致。

## 4. 分类维度与命名

分类只有两级：

1. **一级：场景**（上述三个目录）；
2. **二级：三位序号 + 语义标签**，例如 `001-real-tech2-jvm-gap`。

命名规矩：

- 只用小写 ASCII、数字、连字符；
- 序号三位补零，决定执行顺序与报告顺序；
- 语义标签写「来源 + 特征」，如 `real` / `synthetic` / `negative` / `short` / `no-metrics`；
- **禁止**出现公司名、人名、日期、中文文件名。

## 5. 单份 case 规范

### 5.1 input.txt（必填）

- 编码 UTF-8（无 BOM），换行 LF（仓库 `.gitattributes` 已统一）；
- 只放**送给模型的正文材料**：简历正文 / JD 原文 / 复盘转写或笔记；
- 复盘保留口语重复、错字、断句混乱等原始特征，这是被评测对象的一部分，不要润色；
- 不写标题、不加 Markdown 说明、不写期望答案；
- 建议单份 ≤ 20000 字符，超出先确认是否必要，避免单轮评测成本失控。

### 5.2 meta.json（必填）

通用字段：

| 字段 | 必填 | 取值 / 说明 |
|---|---|---|
| `scene` | 是 | `resume-analysis` / `review-analyze` / `jd-parse`，用于与目录交叉自检 |
| `sourceType` | 是 | `real-transcript` / `real-notes` / `community-rewrite` / `synthetic` / `negative` |
| `sanitized` | 是 | 入库层必须为 `true`；原始层可为 `false` |
| `note` | 否 | 一句话说明样本特征，不写隐私 |

`review-analyze` 额外字段（与复盘 prompt 入参一致）：

| 字段 | 必填 | 取值 |
|---|---|---|
| `companyName` | 是 | 脱敏占位，如 `[公司A]` |
| `jobTitle` | 是 | 岗位名，可保留通用职级 |
| `roundType` | 是 | `HR` / `TECH` / `CROSS` / `DIRECTOR` / `FINAL` / `OTHER` |
| `interviewerRole` | 否 | 自由文本，如「技术负责人」 |
| `format` | 否 | `PHONE` / `VIDEO` / `ONSITE` / `OTHER` |
| `inputType` | 是 | `TEXT`（完整转写）/ `NOTES`（笔记/面经） |

示例：

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

### 5.3 expect.json（必填）

只给评测器读，绝不拼进 prompt。断言分三层：

**L1 硬断言（必须 100% 通过）**

| 键 | 含义 |
|---|---|
| `requiredFields` | 输出必须存在的字段路径 |
| `scoreRange` | 字段分数的合法区间，如 `{"technicalDepth": [0, 70]}` |
| `quotesMustComeFromInput` | 引用的「原句」必须是 `input.txt` 的子串（归一化空白后比对），抓幻觉引用 |
| `maxItems` | 条目数量上限，如 `{"actions": 3, "questions": 50}` |
| `overallFormula` | 总分必须等于本地加权重算值，如 `"30/25/20/15/10"` |

**L2 质量断言（通过率 ≥95%）**

| 键 | 含义 |
|---|---|
| `topicsMustInclude` | 必须命中的知识点 key（词表见附录 A） |
| `lossTagsMustInclude` | 必须命中的失分类型 key |
| `mustMentionAny` | 结论中至少命中一个关键词 |
| `mustNotContainTags` | 禁止出现的词表 key，用于负样本防硬凑 |
| `skillsMin` | JD 解析出的技能数下限 |
| `expectGraceful` | 允许优雅失败（明确报错或空结论），但不得编造 |

**L3 稳定性（同一输入跑 2 次）**

| 键 | 含义 |
|---|---|
| `scoreDriftMax` | 关键分数两次运行的最大漂移，默认 5 |
| `tagJaccardMin` | 失分标签集合两次运行的 Jaccard 相似度下限，默认 0.7 |

三个场景的示例：

```json
{
  "quotesMustComeFromInput": true,
  "scoreRange": { "technicalDepth": [0, 70] },
  "maxItems": { "careerDirections": 5, "strengths": 5 },
  "overallFormula": "30/25/20/15/10",
  "mustMentionAny": ["量化", "指标"],
  "scoreDriftMax": 5
}
```

```json
{
  "topicsMustInclude": ["jvm"],
  "lossTagsMustInclude": ["shallow-depth"],
  "maxItems": { "questions": 20, "actions": 3 },
  "mustMentionAny": ["GC", "垃圾回收"],
  "tagJaccardMin": 0.7,
  "scoreDriftMax": 5
}
```

```json
{
  "requiredFields": ["title", "skills"],
  "skillsMin": 3,
  "maxItems": { "skills": 30 },
  "scoreDriftMax": 5
}
```

负样本示例（纯 HR 轮、没有技术内容）：

```json
{
  "expectGraceful": true,
  "mustNotContainTags": ["knowledge-gap", "shallow-depth"],
  "maxItems": { "questions": 5 }
}
```

### 5.4 可选关联文件

- `jd.txt` / `company-intro.txt` / `resume.txt`：复盘 prompt 的背景材料，存在时评测器按生产同款截断规则注入；
- 音频、截图等二进制**只放原始层**，不入库；如确需入库，先确认已脱敏且体积可接受。

## 6. 首批 22 份配额

| 场景 | 数量 | 构成 |
|---|---|---|
| `review-analyze` | 8 | 001~004 真实转写（自我录音，脱敏）、005~007 面经改写（`NOTES`）、008 负样本（纯 HR 轮） |
| `jd-parse` | 8 | 001~005 手抄改写、006~007 合成边界（只有福利 / 中英混杂）、008 负样本（无职责信息） |
| `resume-analysis` | 6 | 001~003 社区简历改写、004~005 合成缺陷（无量化 / 空窗）、006 超短（≤200 字） |

执行顺序：先做 `review-analyze`（唯一有真实数据的场景），跑通断言后再复制到另外两个场景。

## 7. 脱敏清单

入库前逐项替换：

| 类型 | 处理 |
|---|---|
| 人名、面试官称呼 | `[候选人]` / `[面试官]` |
| 公司、学校 | `[公司A]` / `[学校A]` |
| 手机号、邮箱、微信号 | 直接删除 |
| 项目名、内部系统名 | `[项目X]` / `[系统X]` |
| 独特业务指标（如「日均 300 万单」） | 改为量级描述，如「日均百万级」 |
| 可检索的独特技术组合 | 保留技术事实，调整组合顺序或替换同类组件 |

脱敏后必须自查一遍：把公司名、人名、手机号作为关键词搜索入库文件，确认零命中。

## 8. 基线与变更规则

1. **先冻结基线，再改 prompt**：评测执行器实现后，用当前代码跑一遍全部语料，输出存 `baselines/`；
2. 基线记录每个 case 的 id、关键分数、标签集合、耗时、token；
3. 评测器启动时校验：**基线中的 case id 集合必须与目录扫描结果一致**，不一致直接报错，避免删改语料后 diff 错位；
4. 改语料或改基线都算口径变更，必须单独提交并在提交信息里说明原因；
5. 基线对比出现以下任一情况视为退化：L1 由 PASS 变 FAIL、某 case 分数漂移 >10、标签集合 Jaccard <0.7。

## 9. 建库流程（在另一台主机）

1. `git pull` 拿到本规范；
2. 在仓库外建私有目录，例如 `<语料私有根目录>/raw/{review,jd,resume}/`；
3. 把 4 份真实转写、元数据、你记得的真实失分点放进去（原始层不参与 git）；
4. 在 `backend/src/test/resources/llm-eval/cases/` 下按第 3 节目录结构建 case；
5. 逐份完成脱敏，写 `meta.json` 与 `expect.json`；
6. 按附录 B 自检；
7. 提交入库层（原始层保持未跟踪），push；
8. 把**一个场景目录**的路径告诉 AI，例如 `backend/src/test/resources/llm-eval/cases/review-analyze/`。

## 10. 交付与协作方式

- 不要一次性把 22 份全给 AI：按场景分批，一次 ≤8 份，标注质量才有保证；
- 给路径即可，不必贴正文；
- 需要 AI 帮忙标注时，明确说「读这个目录，补 `expect.json`」，AI 只读入库层，不接触原始层；
- 评测执行器尚未实现，本轮交付物只有语料；实现时复用生产同款 `PromptFactory` 与 `StructuredOutputInvoker`，禁止复制 prompt 文本。

## 附录 A：枚举与词表

- `roundType`：`HR` / `TECH` / `CROSS` / `DIRECTOR` / `FINAL` / `OTHER`
- `inputType`：`AUDIO` / `TEXT` / `NOTES`
- 知识点 `topics`（`taxonomy-v1`）：`java` / `jvm` / `concurrency` / `mysql` / `redis` / `kafka` / `spring` / `distributed` / `system-design` / `algorithm` / `project` / `communication` / `career` / `other`
- 失分类型 `lossTags`（`taxonomy-v1`）：`knowledge-gap` / `shallow-depth` / `unclear-expression` / `no-evidence` / `off-topic` / `time-management` / `pressure` / `other`
- 评分维度：`depth` / `accuracy` / `structure` / `evidence` / `adaptability` / `overall`
- 词表以 `backend/src/main/java/com/jobpilot/modules/review/taxonomy/ReviewTaxonomyCatalog.java` 为准；词表升级时同步升级本附录。

## 附录 B：单份 case 自检清单

- [ ] 目录名符合「三位序号 + 语义标签」，全 ASCII
- [ ] `input.txt` 为 UTF-8 无 BOM、LF，无标题与期望答案
- [ ] `meta.json` 的 `scene` 与所在目录一致
- [ ] `sanitized` 为 `true`，关键词搜索零命中隐私信息
- [ ] `expect.json` 至少包含一条 L1 硬断言
- [ ] 断言使用词表 **key**（如 `jvm`），不是中文 label（如「JVM 与 GC」）
- [ ] 负样本使用 `expectGraceful`，并写清禁止出现的标签
- [ ] 未把原始音频、截图、真实公司名放入入库层
- [ ] 基线存在后，新增/删除 case 已同步更新基线 id 集合
