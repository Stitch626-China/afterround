import { expect, test } from '@playwright/test'

/**
 * M3-b 前端 E2E（接口全 mock）：复盘列表创建/删除、详情修正与确认、改进看板状态流转。
 */

const ok = (data: unknown) => ({ json: { code: 0, message: 'ok', data } })

const now = '2026-11-23T10:00:00Z'

const listItem = (overrides: Record<string, unknown> = {}) => ({
  id: 1,
  interviewEventId: 11,
  applicationId: 5,
  companyName: '字节跳动',
  jobTitle: 'Java 后端开发工程师',
  roundNo: 2,
  roundType: 'TECH',
  inputType: 'TEXT',
  status: 'READY',
  asrStatus: 'COMPLETED',
  analyzeStatus: 'COMPLETED',
  asrRetryCount: 0,
  analyzeRetryCount: 0,
  audioDurationSeconds: null,
  createdAt: now,
  updatedAt: now,
  ...overrides,
})

const application = {
  id: 5,
  jobPositionId: 3,
  jobTitle: 'Java 后端开发工程师',
  companyId: 1,
  companyName: '字节跳动',
  resumeId: null,
  resumeFileName: null,
  channel: 'BOSS',
  appliedAt: now,
  status: 'INTERVIEWING',
  outcome: null,
  notes: null,
  interviewCount: 1,
  latestRoundNo: 1,
  createdAt: now,
  updatedAt: now,
}

const taxonomy = {
  topics: [
    { key: 'jvm', label: 'JVM', description: null },
    { key: 'concurrency', label: '并发编程', description: null },
  ],
  lossTags: [
    { key: 'depth', label: '深度不足', description: null },
    { key: 'structure', label: '结构松散', description: null },
  ],
  dimensions: [
    { key: 'depth', label: '技术深度', description: null, maxScore: 100 },
    { key: 'structure', label: '表达结构', description: null, maxScore: 100 },
  ],
  taxonomyVersion: 'taxonomy-v1',
  rubricVersion: 'interview-rubric-v1',
  updatedAt: now,
}

test('复盘列表：文本创建、展示状态与删除', async ({ page }) => {
  let items = [
    listItem({
      id: 2,
      inputType: 'AUDIO',
      status: 'FAILED',
      asrStatus: 'FAILED',
      analyzeStatus: 'SKIPPED',
      asrError: null,
    }),
  ]
  let createCalled = false
  let createBody = ''
  let deleteCalled = false

  await page.route('**/api/review-taxonomy', (route) => route.fulfill(ok(taxonomy)))
  await page.route(/\/api\/job-applications\?/, (route) =>
    route.fulfill(ok({ items: [application], total: 1, page: 1, size: 100 })),
  )
  await page.route('**/api/review-sessions/2/transcribe', (route) => {
    items = [items[0]]
    return route.fulfill(ok(items[0]))
  })
  await page.route('**/api/review-sessions/2', (route) => {
    deleteCalled = route.request().method() === 'DELETE'
    items = items.filter((item) => item.id !== 2)
    return route.fulfill(ok(null))
  })
  await page.route(/\/api\/review-sessions(\?|$)/, (route) => {
    if (route.request().method() === 'POST') {
      createCalled = true
      createBody = route.request().postData() ?? ''
      const created = listItem({ id: 3, roundNo: 2, inputType: 'TEXT' })
      items = [created, ...items]
      return route.fulfill(ok(created))
    }
    return route.fulfill(ok({ items, total: items.length, page: 1, size: 20 }))
  })

  await page.goto('/reviews')
  await expect(page.getByText('Java 后端开发工程师').first()).toBeVisible()
  await expect(page.locator('.list').getByText('失败')).toBeVisible()
  await expect(page.locator('.list').getByRole('button', { name: '重试转写' })).toBeVisible()

  // 创建复盘：选择投递后轮次自动 +1，切换为转写文本
  await page.locator('.page-head').getByRole('button', { name: '新建复盘' }).click()
  await page.locator('.form label').filter({ hasText: '投递记录' }).locator('select').selectOption('5')
  await expect(page.locator('.form label').filter({ hasText: '轮次号' }).locator('input')).toHaveValue('2')
  await page.getByRole('button', { name: '转写文本' }).click()
  await page.getByPlaceholder('粘贴完整转写文本（建议保留面试官 / 候选人说话人标记）').fill('面试官：讲讲 JVM 内存模型。')
  await page.getByRole('button', { name: '创建复盘' }).click()

  await expect.poll(() => createCalled).toBe(true)
  await expect.poll(() => createBody).toContain('name="transcript"')
  await expect.poll(() => createBody).toContain('name="roundNo"')
  await expect(page.getByText('已创建复盘，分析进行中')).toBeVisible()
  await expect(page.locator('.list .row')).toHaveCount(2)

  // 删除失败的那条录音复盘
  await page.locator('.list .row').filter({ hasText: '录音' }).getByRole('button', { name: '删除' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect.poll(() => deleteCalled).toBe(true)
  await expect(page.locator('.list .row')).toHaveCount(1)
})

test('复盘详情：修正总结与行动并确认，行动同步展示', async ({ page }) => {
  const base = listItem({ id: 1, transcript: '面试官：讲讲 JVM 内存模型。候选人：……', confirmed: false })
  const analysis = {
    questions: [
      {
        questionIndex: 1,
        question: '讲讲 JVM 内存模型',
        answerSummary: '只说了堆和栈。',
        score: 55,
        dimensions: [{ dimension: 'depth', label: '技术深度', score: 55 }],
        topics: ['jvm'],
        lossTags: ['depth'],
        evidence: '只说了堆和栈。',
        feedback: '缺少方法区、程序计数器与直接内存。',
        referenceAnswer: '按线程共享 / 私有分区回答。',
      },
    ],
    topics: [{ tag: 'jvm', label: 'JVM', confidence: 0.9, evidence: '内存模型' }],
    lossTags: [{ tag: 'depth', label: '深度不足', confidence: 0.8, evidence: null }],
    summary: {
      overall: '基础概念有，但深度不足。',
      strengths: ['回答直接'],
      improvements: ['补 JVM 分区'],
      nextPreparation: ['手写内存模型'],
    },
    actions: [{ action: '整理 JVM 内存模型笔记', category: '知识点补强', reason: '深度不足' }],
    promptVersion: 'review-analyze-v1',
    rubricVersion: 'interview-rubric-v1',
    taxonomyVersion: 'taxonomy-v1',
    confirmed: false,
    degraded: false,
    llmProvider: 'stub',
    llmModel: 'm',
  }
  let confirmed = false
  let putPayload: Record<string, unknown> | null = null
  let eventResult = 'PENDING'
  let resultPayload: Record<string, unknown> | null = null
  const event = {
    id: 11,
    applicationId: 5,
    jobPositionId: 3,
    companyId: 1,
    companyName: '字节跳动',
    jobTitle: 'Java 后端开发工程师',
    roundNo: 2,
    roundType: 'TECH',
    interviewerRole: null,
    format: null,
    interviewTime: now,
    result: eventResult,
    notes: null,
    reviewSessionId: 1,
    reviewStatus: 'READY',
    createdAt: now,
    updatedAt: now,
  }

  await page.route('**/api/review-taxonomy', (route) => route.fulfill(ok(taxonomy)))
  await page.route(/\/api\/interview-events\?/, (route) =>
    route.fulfill(ok({ items: [{ ...event, result: eventResult }], total: 1, page: 1, size: 100 })),
  )
  await page.route('**/api/interview-events/11', (route) => {
    resultPayload = route.request().postDataJSON() as Record<string, unknown>
    eventResult = (resultPayload.result as string) ?? eventResult
    return route.fulfill(ok({ ...event, result: eventResult }))
  })
  await page.route(/\/api\/improvement-actions\?/, (route) =>
    route.fulfill(
      ok({
        items: confirmed
          ? [
              {
                id: 7,
                sourceReviewId: 1,
                sourceEventId: 11,
                applicationId: 5,
                action: '整理 JVM 内存模型笔记',
                category: '知识点补强',
                status: 'TODO',
                dueAt: null,
                verifiedEventId: null,
                evidence: null,
                createdAt: now,
                updatedAt: now,
              },
            ]
          : [],
        total: confirmed ? 1 : 0,
        page: 1,
        size: 50,
      }),
    ),
  )
  await page.route('**/api/review-sessions/1/analysis', (route) => {
    putPayload = route.request().postDataJSON() as Record<string, unknown>
    confirmed = (putPayload.confirmed as boolean) ?? false
    return route.fulfill(
      ok({
        ...base,
        confirmed,
        analysis: { ...analysis, confirmed, summary: putPayload.summary, actions: putPayload.actions },
      }),
    )
  })
  await page.route(/\/api\/review-sessions\/1$/, (route) =>
    route.fulfill(ok({ ...base, confirmed, analysis: { ...analysis, confirmed } })),
  )

  await page.goto('/reviews/1')
  await expect(page.getByRole('heading', { name: /Java 后端开发工程师/ })).toBeVisible()
  await expect(page.getByText('只说了堆和栈。').first()).toBeVisible()
  await expect(page.getByText('深度不足').first()).toBeVisible()
  await expect(page.getByText('JVM').first()).toBeVisible()

  // 本轮结果可编辑（F5 归因依赖该字段）
  await expect(page.getByLabel('本轮面试结果')).toHaveValue('PENDING')
  await page.getByLabel('本轮面试结果').selectOption('PASS')
  await expect.poll(() => resultPayload?.result).toBe('PASS')
  await expect(page.getByText('本轮结果已标记为「通过」')).toBeVisible()

  // 修正总结与行动后确认
  await page.locator('label').filter({ hasText: '整体总结' }).locator('textarea').fill('整体基础一般，需要补深度。')
  await page.getByPlaceholder('具体、可执行的改进动作').fill('整理 JVM 内存模型笔记并口述')
  await page.getByRole('button', { name: '确认分析并生成行动' }).click()

  await expect.poll(() => putPayload?.confirmed).toBe(true)
  const sentActions = putPayload?.actions as Array<{ action: string }>
  expect(sentActions[0].action).toBe('整理 JVM 内存模型笔记并口述')
  expect(sentActions).toHaveLength(1)
  await expect(page.getByText('分析已确认，改进行动已同步到改进看板')).toBeVisible()
  await expect(page.locator('.status-line').getByText('已确认')).toBeVisible()
  await expect(page.getByText('整理 JVM 内存模型笔记', { exact: false })).toBeVisible()
})

test('改进看板：状态流转、新增行动与验证证据', async ({ page }) => {
  let status = 'TODO'
  let patchPayload: Record<string, unknown> | null = null
  let createPayload: Record<string, unknown> | null = null

  await page.route('**/api/improvement-actions/7', (route) => {
    patchPayload = route.request().postDataJSON() as Record<string, unknown>
    if (patchPayload.status) {
      status = patchPayload.status as string
    }
    return route.fulfill(
      ok({
        id: 7,
        sourceReviewId: 1,
        sourceEventId: 11,
        applicationId: 5,
        action: '整理 JVM 内存模型笔记',
        category: '知识点补强',
        status,
        dueAt: null,
        verifiedEventId: null,
        evidence: (patchPayload.evidence as string) ?? null,
        createdAt: now,
        updatedAt: now,
      }),
    )
  })
  await page.route(/\/api\/improvement-actions(\?|$)/, (route) => {
    if (route.request().method() === 'POST') {
      createPayload = route.request().postDataJSON() as Record<string, unknown>
      return route.fulfill(
        ok({
          id: 8,
          sourceReviewId: null,
          sourceEventId: null,
          applicationId: null,
          action: createPayload.action,
          category: createPayload.category ?? null,
          status: 'TODO',
          dueAt: null,
          verifiedEventId: null,
          evidence: null,
          createdAt: now,
          updatedAt: now,
        }),
      )
    }
    return route.fulfill(
      ok({
        items: [
          {
            id: 7,
            sourceReviewId: 1,
            sourceEventId: 11,
            applicationId: 5,
            action: '整理 JVM 内存模型笔记',
            category: '知识点补强',
            status,
            dueAt: null,
            verifiedEventId: null,
            evidence: status === 'VERIFIED' ? '二面能完整说出分区' : null,
            createdAt: now,
            updatedAt: now,
          },
        ],
        total: 1,
        page: 1,
        size: 20,
      }),
    )
  })

  await page.goto('/improvements')
  await expect(page.getByText('整理 JVM 内存模型笔记')).toBeVisible()
  await expect(page.getByText('来源复盘 #1')).toBeVisible()

  // 状态流转
  await page.getByLabel('更新行动状态').selectOption('IN_PROGRESS')
  await expect.poll(() => patchPayload?.status).toBe('IN_PROGRESS')
  await expect(page.getByText('已更新为「进行中」')).toBeVisible()

  // 验证证据
  await page.getByRole('button', { name: '验证证据' }).click()
  await page.getByPlaceholder('写下验证方式与结果').fill('二面能完整说出分区')
  await page.getByRole('button', { name: '保存证据' }).click()
  await expect.poll(() => patchPayload?.evidence).toBe('二面能完整说出分区')
  await expect(page.getByText('验证证据已保存')).toBeVisible()

  // 新增行动
  await page.locator('.page-head').getByRole('button', { name: '新增行动' }).click()
  await page.locator('.form textarea').fill('复盘后补并发容器笔记')
  await page.locator('.form input').first().fill('知识点补强')
  await page.getByRole('button', { name: '保存' }).click()
  await expect.poll(() => createPayload?.action).toBe('复盘后补并发容器笔记')
  await expect(page.getByText('已添加改进行动')).toBeVisible()
})
