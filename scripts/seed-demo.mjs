#!/usr/bin/env node
/**
 * 面后（AfterRound）演示数据种子。
 *
 * 通过真实 API 造一条完整求职链路：
 *   公司 → JD → 简历 → 匹配 → 投递 → 面试轮次 → 复盘 → 改进 → Offer
 *
 * 前置条件：后端已启动，且已配置全局默认模型（分析类数据需要调用模型）。
 * 用法：node scripts/seed-demo.mjs [baseUrl]
 * 环境变量：SEED_BASE_URL（默认 http://localhost:8080）、SEED_TIMEOUT_SECONDS（单任务超时，默认 1200）
 *
 * 幂等：公司按名称、岗位按「公司 + 标题」、简历按分析出的姓名、投递按岗位、
 * 复盘按「投递 + 轮次」、Offer 按关联投递复用；重复执行不会重复造数。
 */

const BASE = (process.env.SEED_BASE_URL || process.argv[2] || 'http://localhost:8080').replace(/\/+$/, '');
const POLL_MS = 2500;
const TIMEOUT_MS = Number(process.env.SEED_TIMEOUT_SECONDS || 1200) * 1000;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const log = (msg) => console.log(`[seed] ${msg}`);
const isoDaysAgo = (days) => new Date(Date.now() - days * 86400000).toISOString();

const COMPANIES = [
  {
    name: '星河科技',
    intro: '虚构示例公司：企业服务与交易平台，团队约 300 人，业务覆盖订单、支付与履约。',
    tags: ['企业服务', '交易平台', '双休'],
  },
  {
    name: '云帆数据',
    intro: '虚构示例公司：数据智能与报表平台，为零售与制造客户提供数据分析服务。',
    tags: ['数据平台', 'SaaS'],
  },
];

const JOBS = [
  {
    company: '星河科技',
    title: 'Java 后端开发工程师',
    city: '上海',
    salaryMin: 25000,
    salaryMax: 35000,
    text: `公司：星河科技
岗位：Java 后端开发工程师
城市：上海
薪资：25K-35K · 14 薪
经验：3-5 年
学历：本科及以上

岗位职责
1. 负责核心交易系统的服务端设计与开发，保障高并发下的稳定性；
2. 参与订单、支付、履约链路的性能优化与故障排查；
3. 参与技术方案评审，沉淀可复用的中间件与工具；
4. 与产品、测试协作，持续提升交付质量。

任职要求
1. 扎实的 Java 基础，熟悉并发编程、JVM 内存模型与 GC 调优；
2. 熟悉 Spring Boot、MyBatis、MySQL，具备索引优化经验；
3. 熟悉 Redis、RocketMQ 等常用中间件，理解缓存与消息可靠性；
4. 有高并发、分布式系统经验，能独立排查线上问题；
5. 本科及以上学历，3 年以上后端开发经验。

加分项
1. 有 K8s、Docker 使用经验；
2. 有 Prometheus / Grafana 监控体系建设经验；
3. 有开源项目或技术博客。`,
  },
  {
    company: '云帆数据',
    title: '高级 Java 工程师',
    city: '杭州',
    salaryMin: 28000,
    salaryMax: 38000,
    text: `公司：云帆数据
岗位：高级 Java 工程师
城市：杭州
薪资：28K-38K · 13 薪
经验：3-5 年
学历：本科及以上

岗位职责
1. 负责数据服务平台的后端架构与核心模块开发；
2. 设计并优化数据查询与导出链路，提升大数据量下的响应速度；
3. 参与服务治理、限流降级与可观测性建设。

任职要求
1. 熟悉 Java 并发与 JVM 调优，具备线上问题定位能力；
2. 熟悉 Spring Cloud / Spring Boot、MySQL、Redis；
3. 熟悉消息队列与分布式事务，了解数据一致性方案；
4. 有数据平台或报表系统经验优先。

加分项
1. 熟悉 Elasticsearch；
2. 有 K8s 部署经验。`,
  },
];

const RESUME = {
  name: '张伟',
  text: `张伟
求职意向：Java 后端开发工程师
手机：138****0000  邮箱：zhangwei@example.com  城市：上海
工作年限：3 年

工作经历
2022.07 - 至今  星云网络科技有限公司  Java 后端开发工程师
- 负责订单中心与结算服务，日均订单 30 万，接口 P99 从 420ms 降到 180ms。
- 主导订单查询缓存重构：本地缓存 + Redis 二级缓存 + 热点探测，缓存命中率从 62% 提升到 93%。
- 使用 RocketMQ 解耦支付回调与订单状态更新，消息积压从 20 万降到 2000 以内。
- 推动接口幂等与对账任务，线上重复扣款问题从每月 3 起降到 0。

2021.07 - 2022.06  启明数据  Java 开发工程师
- 参与数据报表平台开发，负责权限与导出模块，支撑 200+ 内部用户。
- 使用 Spring Boot + MyBatis 重构报表查询，慢 SQL 数量下降 70%。

项目经历
订单中心高并发改造
- 技术栈：Java 17、Spring Boot、MySQL、Redis、RocketMQ、Prometheus
- 设计本地缓存 + Redis 二级缓存与布隆过滤器，拦截无效查询；压测 QPS 从 3k 提升到 9k。
- 使用分布式锁与状态机保证支付回调幂等，补充对账补偿任务。

技能
- 熟悉 Java 并发、JVM 内存模型与常见 GC 调优，熟悉 Spring / Spring Boot / MyBatis。
- 熟悉 MySQL 索引与执行计划、Redis 缓存设计、RocketMQ，了解 Docker 与 K8s 基本使用。
- 了解常用设计模式与 DDD 分层，具备接口文档与单元测试习惯。

教育
2017.09 - 2021.06  华东理工大学  软件工程  本科`,
};

const APPLICATIONS = [
  { company: '星河科技', channel: 'BOSS', daysAgo: 35, notes: '内推，流程较快' },
  { company: '云帆数据', channel: '官网', daysAgo: 28, notes: '官网投递，HR 电话约面' },
];

const REVIEWS = [
  {
    company: '星河科技',
    roundNo: 1,
    roundType: 'TECH',
    result: 'PASS',
    interviewerRole: '后端组长',
    format: 'VIDEO',
    daysAgo: 30,
    transcript: `面试官：先做个自我介绍吧。
我：我是张伟，3 年 Java 后端经验，目前在星云网络负责订单中心，主要做交易链路和稳定性。
面试官：你提到订单查询优化，具体怎么做的？
我：先看监控，P99 420ms，主要是热点商品查询打满数据库。我做了本地缓存加 Redis 二级缓存，热点探测，再用布隆过滤器拦截无效 key，命中率从 62% 到 93%。
面试官：缓存一致性怎么保证？
我：更新时先更新数据库再删除缓存，延迟双删兜底；强一致场景直接读库。
面试官：Redis 热 key 怎么处理？
我：本地缓存加热点探测，把热 key 打散到多个 key，具体扩容和限流这块我当时参与得不多。
面试官：JVM 调过吗？
我：做过一次 GC 调优，G1 调整 MaxGCPauseMillis，Full GC 从一天 3 次降到 0。
面试官：MQ 消息积压怎么处理？
我：先扩容消费者，再排查慢消费，必要时临时转存到新 topic 再回放。
面试官：你有什么问题？
我：想了解团队的技术栈和业务节奏。`,
  },
  {
    company: '星河科技',
    roundNo: 2,
    roundType: 'CROSS',
    result: 'PASS',
    interviewerRole: '技术负责人',
    format: 'VIDEO',
    daysAgo: 24,
    transcript: `面试官：设计一个秒杀下单系统。
我：先分层，网关限流，库存预热到 Redis，Lua 脚本原子扣减，MQ 异步下单，本地消息表保证最终一致。
面试官：超卖怎么防？
我：Redis Lua 原子扣减加数据库唯一约束兜底，扣减和订单创建都做幂等。
面试官：MQ 丢消息怎么办？
我：生产端 confirm，消费端手动 ack，本地消息表补偿，消费失败进入重试队列。
面试官：流量突增怎么扩容？
我：加机器，嗯，具体容量评估和降级策略我这边经验不多。
面试官：线上 CPU 飙高怎么排查？
我：top -Hp 找到线程，jstack 看栈，再结合 arthas 看热点方法，最后做火焰图。
面试官：你觉得自己这次表现怎么样？
我：整体思路清楚，扩容和容量评估还需要补。`,
  },
  {
    company: '星河科技',
    roundNo: 3,
    roundType: 'FINAL',
    result: 'PASS',
    interviewerRole: '部门总监',
    format: 'ONSITE',
    daysAgo: 18,
    transcript: `面试官：讲一个你最有成就感的项目。
我：订单中心缓存重构，把 P99 从 420ms 降到 180ms，重复扣款也做到 0。
面试官：如果让你重做，会怎么改？
我：先做容量评估和压测基线，再引入多级缓存和热点治理，最后补监控看板。
面试官：为什么考虑换工作？
我：希望做更大流量、更完整的交易链路，也想系统补一下稳定性建设。
面试官：未来三年规划？
我：成为交易领域的资深后端，能独立负责一个核心系统的架构和稳定性。
面试官：你对加班怎么看？
我：项目关键期可以投入，但更希望靠流程和工具减少无效加班。`,
  },
  {
    company: '云帆数据',
    roundNo: 1,
    roundType: 'TECH',
    result: 'PASS',
    interviewerRole: '后端负责人',
    format: 'PHONE',
    daysAgo: 22,
    transcript: `面试官：介绍一个你负责的模块。
我：报表平台的权限和导出模块，支撑 200 多内部用户。
面试官：大文件导出怎么做的？
我：分页查询加流式写出，避免一次性加载导致 OOM，导出任务也做了限流。
面试官：MySQL 慢 SQL 怎么优化？
我：先 explain 看索引，尽量覆盖索引、避免回表，必要时拆分查询。
面试官：线程池参数怎么设置？
我：按 CPU 密集和 IO 密集区分核心线程数，队列长度和拒绝策略要看业务，我一般会先压测再定。
面试官：你还有什么想问的？
我：数据平台目前的数据量和主要挑战。`,
  },
  {
    company: '云帆数据',
    roundNo: 2,
    roundType: 'TECH',
    result: 'FAIL',
    interviewerRole: '技术总监',
    format: 'ONSITE',
    daysAgo: 15,
    transcript: `面试官：设计一个亿级数据的报表查询。
我：考虑分库分表，再加预聚合，查询走聚合结果表。
面试官：预聚合的时效性怎么保证？
我：用定时任务刷新，具体增量和回刷这块我没有做过。
面试官：Elasticsearch 和 ClickHouse 怎么选？
我：这个我了解不多，只知道 ES 适合搜索。
面试官：分布式事务怎么处理？
我：用过 Seata 的 AT 模式，隔离和补偿细节记得不太清楚。
面试官：你觉得这次面试表现如何？
我：数据平台经验不足，很多问题只停留在概念，需要补实践。`,
  },
];

const OFFER = {
  monthlySalary: 28000,
  baseSalary: 22000,
  performanceRatio: 21,
  socialInsuranceBase: '按实际工资',
  socialInsuranceRatio: '10.5%',
  housingFundBase: '按实际工资',
  housingFundRatio: '12%',
  twoDayWeekend: true,
  workHours: '10:00-19:00',
  benefits: ['餐补', '免费班车', '补充医疗'],
  allowances: ['交通补贴 500/月'],
  annualBonus: '3 个月',
  quarterlyBonus: '无',
  remark: '演示数据：Offer 决策对比样例',
  group: 'KEEP',
};

/** 统一请求：成功返回 data，失败抛出带业务码的错误。 */
async function call(method, path, { json, form } = {}) {
  const headers = {};
  let body;
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(json);
  } else if (form) {
    body = form;
  }
  let response;
  try {
    response = await fetch(`${BASE}${path}`, { method, headers, body });
  } catch (error) {
    throw new Error(`无法连接后端 ${BASE}：${error.message}（请先启动后端）`);
  }
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    throw new Error(`${method} ${path} 返回非 JSON（HTTP ${response.status}）`);
  }
  if (!response.ok || (payload && payload.code !== 0)) {
    throw new Error(`${method} ${path} 失败：${payload?.message ?? `HTTP ${response.status}`}（code=${payload?.code ?? response.status}）`);
  }
  return payload?.data;
}

/** 轮询直到 accept(state) 为真；FAILED 立即抛错。 */
async function waitTask(label, path, accept, stateOf, errorOf = (data) => data.error) {
  const start = Date.now();
  for (;;) {
    const data = await call('GET', path);
    const state = stateOf(data);
    if (accept(state, data)) return data;
    if (state === 'FAILED') throw new Error(`${label} 失败：${errorOf(data) ?? '未知原因'}`);
    if (Date.now() - start > TIMEOUT_MS) {
      throw new Error(`${label} 超时（${TIMEOUT_MS / 1000}s），最后状态：${state}`);
    }
    await sleep(POLL_MS);
  }
}

async function ensureCompany(def) {
  const list = await call('GET', `/api/companies?page=1&size=100&keyword=${encodeURIComponent(def.name)}`);
  const hit = list.items.find((item) => item.name === def.name);
  if (hit) {
    log(`公司已存在：${def.name}`);
    return hit;
  }
  const created = await call('POST', '/api/companies', { json: def });
  log(`公司已创建：${def.name}`);
  return created;
}

async function ensureJob(def, company) {
  const list = await call('GET', `/api/job-positions?page=1&size=100&keyword=${encodeURIComponent(def.title)}`);
  for (const item of list.items) {
    if (item.title !== def.title) continue;
    const job = item.parseStatus === 'COMPLETED' ? item
      : await waitTask(`JD 解析（${def.title}）`, `/api/job-positions/${item.id}`,
        (state) => state === 'COMPLETED', (data) => data.parseStatus, (data) => data.parseError);
    if (job.companyName === company.name) {
      log(`岗位已存在：${company.name} · ${def.title}`);
      return job;
    }
  }
  const form = new FormData();
  form.append('text', def.text);
  let job = await call('POST', '/api/job-positions', { form });
  log(`岗位已导入：${company.name} · ${def.title}（等待解析）`);
  job = await waitTask(`JD 解析（${def.title}）`, `/api/job-positions/${job.id}`,
    (state) => state === 'COMPLETED', (data) => data.parseStatus, (data) => data.parseError);
  job = await call('PUT', `/api/job-positions/${job.id}`, {
    json: { companyId: company.id, city: def.city, salaryMin: def.salaryMin, salaryMax: def.salaryMax },
  });
  return job;
}

async function ensureResume() {
  const list = await call('GET', '/api/resumes?page=1&size=100');
  for (const item of list.items) {
    if (item.analyzeStatus !== 'COMPLETED') continue;
    const status = await call('GET', `/api/resumes/${item.id}/analysis`);
    if (status.report?.basicInfo?.name === RESUME.name) {
      log(`简历已存在：${RESUME.name}（总分 ${status.report.overallScore}）`);
      return item;
    }
  }
  const form = new FormData();
  form.append('text', RESUME.text);
  const uploaded = await call('POST', '/api/resumes', { form });
  const resume = uploaded.resume;
  log(uploaded.duplicated ? `简历命中已有记录：${resume.id}` : `简历已上传：${RESUME.name}（等待分析）`);
  const status = await call('GET', `/api/resumes/${resume.id}/analysis`);
  if (status.status === 'FAILED') await call('POST', `/api/resumes/${resume.id}/analyze`);
  const done = await waitTask(`简历分析（${RESUME.name}）`, `/api/resumes/${resume.id}/analysis`,
    (state) => state === 'COMPLETED', (data) => data.status);
  log(`简历分析完成：${done.report?.overallScore} 分`);
  return resume;
}

async function ensureMatch(resumeId, job) {
  const list = await call('GET', `/api/job-matches?page=1&size=20&resumeId=${resumeId}&jobPositionId=${job.id}`);
  let match = list.items[0];
  if (match?.status === 'COMPLETED') {
    log(`匹配已存在：${job.companyName} · ${job.title}（${match.overallScore} 分）`);
    return match;
  }
  if (!match || match.status === 'FAILED') {
    match = await call('POST', '/api/job-matches', { json: { resumeId, jobPositionId: job.id } });
    log(`匹配已发起：${job.companyName} · ${job.title}`);
  }
  match = await waitTask(`简历-JD 匹配（${job.title}）`, `/api/job-matches/${match.id}`,
    (state) => state === 'COMPLETED', (data) => data.status);
  log(`匹配完成：${match.overallScore} 分`);
  return match;
}

async function ensureApplication(def, job, resume) {
  const list = await call('GET', `/api/job-applications?page=1&size=100&jobPositionId=${job.id}`);
  const hit = list.items[0];
  if (hit) {
    log(`投递已存在：${job.companyName} · ${job.title}`);
    return hit;
  }
  const created = await call('POST', '/api/job-applications', {
    json: {
      jobPositionId: job.id,
      resumeId: resume.id,
      channel: def.channel,
      appliedAt: isoDaysAgo(def.daysAgo),
      notes: def.notes,
    },
  });
  log(`投递已创建：${job.companyName} · ${job.title}`);
  return created;
}

async function prepareReview(def, application) {
  const list = await call('GET', `/api/review-sessions?page=1&size=100&applicationId=${application.id}`);
  const hit = list.items.find((item) => item.roundNo === def.roundNo);
  if (hit) {
    log(`复盘已存在：${def.company} 第 ${def.roundNo} 轮`);
    return hit;
  }
  const form = new FormData();
  form.append('transcript', def.transcript);
  form.append('applicationId', String(application.id));
  form.append('roundNo', String(def.roundNo));
  form.append('roundType', def.roundType);
  form.append('interviewerRole', def.interviewerRole);
  form.append('format', def.format);
  form.append('interviewTime', isoDaysAgo(def.daysAgo));
  const created = await call('POST', '/api/review-sessions', { form });
  log(`复盘已创建：${def.company} 第 ${def.roundNo} 轮（等待分析）`);
  return created;
}

async function finalizeReview(def, review) {
  const statusPath = `/api/review-sessions/${review.id}/status`;
  const status = await call('GET', statusPath);
  if (status.status === 'FAILED') {
    await call('POST', `/api/review-sessions/${review.id}/analyze`);
    log(`复盘分析重试：${def.company} 第 ${def.roundNo} 轮`);
  }
  await waitTask(`复盘分析（${def.company} 第 ${def.roundNo} 轮）`, statusPath,
    (state) => state === 'READY', (data) => data.status,
    (data) => data.analyzeError ?? data.asrError);
  let detail = await call('GET', `/api/review-sessions/${review.id}`);
  if (!detail.confirmed) {
    const analysis = detail.analysis;
    detail = await call('PUT', `/api/review-sessions/${review.id}/analysis`, {
      json: {
        questions: analysis.questions,
        topics: analysis.topics,
        lossTags: analysis.lossTags,
        summary: analysis.summary,
        actions: analysis.actions,
        confirmed: true,
      },
    });
    log(`复盘已确认：${def.company} 第 ${def.roundNo} 轮（${analysis.questions.length} 题）`);
  }
  await call('PATCH', `/api/interview-events/${detail.interviewEventId}`, { json: { result: def.result } });
  let actions = (await call('GET', `/api/improvement-actions?page=1&size=100&sourceReviewId=${review.id}`)).items;
  if (!actions.length) {
    const fallback = detail.analysis?.summary?.improvements?.[0] ?? `复盘改进：${def.company} 第 ${def.roundNo} 轮`;
    const created = await call('POST', '/api/improvement-actions', {
      json: { action: fallback, category: '表达结构', sourceReviewId: review.id },
    });
    actions = [created];
  }
  return { reviewId: review.id, actions };
}

async function markActions(reviews) {
  const all = reviews.flatMap((item) => item.actions);
  const plan = [
    { action: all[0], status: 'VERIFIED', evidence: '在下一场交叉面中按 STAR 结构重述项目背景与取舍，面试官未再追问细节' },
    { action: all[1], status: 'VERIFIED', evidence: '补充 JVM 调优案例并写进项目复盘，终面复述时能讲清排查路径' },
    { action: all[2], status: 'IN_PROGRESS', evidence: null },
  ];
  for (const item of plan) {
    if (!item.action) continue;
    await call('PATCH', `/api/improvement-actions/${item.action.id}`, {
      json: { status: item.status, evidence: item.evidence },
    });
  }
  log(`改进行动：共 ${all.length} 条，已标记 2 条已验证 / 1 条进行中`);
}

async function ensureOffer(application, job) {
  for (const group of ['KEEP', 'DROP']) {
    const list = await call('GET', `/api/offers?page=1&size=100&group=${group}`);
    const hit = list.items.find((item) => item.applicationId === application.id);
    if (hit) {
      log(`Offer 已存在：${hit.monthlySalary} 元/月 · ${hit.group}`);
      return hit;
    }
  }
  const created = await call('POST', '/api/offers', {
    json: {
      ...OFFER,
      companyId: job.companyId,
      jobPositionId: job.id,
      applicationId: application.id,
    },
  });
  log(`Offer 已创建：${OFFER.monthlySalary} 元/月 × 14 薪（保留组）`);
  return created;
}

async function main() {
  log(`后端地址：${BASE}`);
  const global = await call('GET', '/api/llm/global');
  if (!global?.defaultProviderKey || !global?.defaultModel) {
    throw new Error('尚未设置全局默认模型：请打开「设置 → 模型服务」，将某个模型设为全局默认后再运行');
  }
  log(`全局模型：${global.defaultProviderKey} / ${global.defaultModel}`);

  const companies = {};
  for (const def of COMPANIES) companies[def.name] = await ensureCompany(def);

  const jobs = {};
  for (const def of JOBS) jobs[def.company] = await ensureJob(def, companies[def.company]);

  const resume = await ensureResume();
  await ensureMatch(resume.id, jobs['星河科技']);

  const applications = {};
  for (const def of APPLICATIONS) {
    applications[def.company] = await ensureApplication(def, jobs[def.company], resume);
  }

  const prepared = [];
  for (const def of REVIEWS) prepared.push({ def, review: await prepareReview(def, applications[def.company]) });
  const finalized = [];
  for (const item of prepared) finalized.push(await finalizeReview(item.def, item.review));
  await markActions(finalized);

  await ensureOffer(applications['星河科技'], jobs['星河科技']);

  await call('PATCH', `/api/job-applications/${applications['星河科技'].id}`, {
    json: { status: 'OFFERED', outcome: 'Offer 已发：28K × 14' },
  });
  await call('PATCH', `/api/job-applications/${applications['云帆数据'].id}`, {
    json: { status: 'REJECTED', outcome: '二面未通过：数据平台经验不足' },
  });
  await call('PUT', `/api/job-positions/${jobs['星河科技'].id}`, { json: { status: 'OFFERED' } });
  await call('PUT', `/api/job-positions/${jobs['云帆数据'].id}`, { json: { status: 'REJECTED' } });

  const insights = await call('GET', '/api/insights?days=90');
  const sample = insights.sample;
  log(`洞察样本：投递 ${sample.applications} / 面试 ${sample.interviews} / 复盘 ${sample.confirmedReviews} / Offer ${sample.offers}（${sample.sufficient ? '样本充足' : '样本不足'}）`);
  log('完成。可打开：简历中心 / 岗位库 / 面试复盘 / 求职洞察 / Offer 决策');
}

main().catch((error) => {
  console.error(`\n[seed] 失败：${error.message}`);
  process.exitCode = 1;
});
