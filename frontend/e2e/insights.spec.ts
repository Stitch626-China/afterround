import { expect, test } from '@playwright/test'

/**
 * M3-c 前端 E2E（接口全 mock）：跨场洞察的样本保护、各聚合区块与空状态跳转。
 */

const ok = (data: unknown) => ({ json: { code: 0, message: 'ok', data } })

const overview = {
  sample: {
    applications: 10,
    interviews: 6,
    reviews: 4,
    confirmedReviews: 3,
    offers: 2,
    minimum: 5,
    sufficient: false,
  },
  summary: {
    content: '已确认复盘 3 份，少于 5 份，样本不足，暂不输出因果结论；以下仅展示原始统计。',
    basedOn: 'LOCAL_AGGREGATION',
  },
  topics: [
    {
      topic: 'jvm',
      label: 'JVM 与 GC',
      interviewCount: 4,
      passCount: 1,
      failCount: 2,
      pendingCount: 1,
      failRate: 0.6667,
      sufficient: false,
    },
    {
      topic: 'redis',
      label: 'Redis',
      interviewCount: 2,
      passCount: 2,
      failCount: 0,
      pendingCount: 0,
      failRate: 0,
      sufficient: false,
    },
  ],
  lossTags: [
    { tag: 'shallow-depth', label: '深度不足', count: 3, lastSeenAt: '2026-11-20T10:00:00Z' },
    { tag: 'unclear-expression', label: '表达不清', count: 1, lastSeenAt: '2026-11-18T10:00:00Z' },
  ],
  roundPassRates: [
    {
      roundType: 'TECH',
      label: '技术面',
      total: 4,
      passCount: 1,
      failCount: 2,
      pendingCount: 1,
      passRate: 0.3333,
      sufficient: false,
    },
  ],
  companyProfiles: [
    {
      companyId: 1,
      companyName: '字节跳动',
      interviewCount: 3,
      passCount: 1,
      failCount: 2,
      pendingCount: 0,
      topTopics: [{ tag: 'jvm', label: 'JVM 与 GC', count: 2, lastSeenAt: null }],
      commonLossTags: [{ tag: 'shallow-depth', label: '深度不足', count: 2, lastSeenAt: null }],
      sufficient: false,
      lastInterviewAt: '2026-11-20T10:00:00Z',
    },
  ],
  topQuestions: [
    { question: '讲讲 JVM 内存模型', askedCount: 2, lastAskedAt: '2026-11-20T10:00:00Z' },
    { question: 'Redis 缓存一致性怎么保证', askedCount: 1, lastAskedAt: '2026-11-18T10:00:00Z' },
  ],
  funnel: {
    applications: 10,
    interviews: 6,
    offers: 2,
    rejected: 3,
    withdrawn: 1,
    interviewRate: 0.6,
    offerRate: 0.2,
  },
  resumeFunnel: [
    {
      resumeId: 3,
      fileName: 'resume-v3.pdf',
      applications: 6,
      interviews: 4,
      offers: 2,
      passed: 1,
      rejected: 3,
      interviewRate: 0.6667,
      passRate: 0.25,
      sufficient: true,
    },
  ],
  channels: [
    {
      label: 'BOSS',
      applications: 6,
      interviews: 4,
      offers: 2,
      interviewRate: 0.6667,
      offerRate: 0.3333,
      sufficient: true,
    },
  ],
  companyTypes: [
    {
      label: '企业服务',
      applications: 4,
      interviews: 2,
      offers: 1,
      interviewRate: 0.5,
      offerRate: 0.25,
      sufficient: false,
    },
  ],
  improvements: {
    total: 4,
    todo: 1,
    inProgress: 1,
    verified: 1,
    dropped: 1,
    verificationRate: 0.3333,
    staleActions: [
      { id: 7, action: '补 JVM 笔记', status: 'TODO', createdAt: '2026-10-01T10:00:00Z' },
    ],
  },
  trend: {
    windowDays: 90,
    currentReviews: 3,
    previousReviews: 2,
    sufficient: false,
    lossTagDeltas: [
      { tag: 'shallow-depth', label: '深度不足', currentCount: 3, previousCount: 1, delta: 2 },
    ],
    passRateDelta: null,
  },
  taxonomyVersion: 'taxonomy-v1',
  generatedAt: '2026-11-23T10:00:00Z',
}

test('求职洞察：样本不足提示、聚合区块与窗口筛选', async ({ page }) => {
  let lastQuery = ''
  let summaryBody = ''

  await page.route('**/api/companies?*', (route) =>
    route.fulfill(ok({ items: [{ id: 1, name: '字节跳动', tags: [], jobPositionCount: 3 }], total: 1, page: 1, size: 100 })),
  )
  await page.route('**/api/insights?*', (route) => {
    lastQuery = route.request().url()
    return route.fulfill(ok(overview))
  })
  await page.route('**/api/insights/summary', (route) => {
    summaryBody = route.request().postData() ?? ''
    return route.fulfill(
      ok({
        content: '整体表现稳定，优先补 JVM 深度。',
        degraded: false,
        sample: overview.sample,
        provider: 'corp',
        model: 'flash',
        generatedAt: '2026-11-26T10:00:00Z',
      }),
    )
  })
  await page.route('**/api/insights/interview-events?*', (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 8 })),
  )
  await page.route('**/api/review-taxonomy', (route) =>
    route.fulfill(
      ok({
        topics: [{ key: 'jvm', label: 'JVM 与 GC', description: null }],
        lossTags: [],
        dimensions: [],
        taxonomyVersion: 'taxonomy-v1',
        rubricVersion: 'rubric-v1',
        updatedAt: null,
      }),
    ),
  )

  await page.goto('/insights')
  await expect(page.getByText('已确认复盘 3 份 · 门槛 5 份')).toBeVisible()
  await expect(page.getByText('样本不足（N < 5）：只展示原始统计，不输出因果结论。')).toBeVisible()
  await expect(page.getByText('暂不输出因果结论', { exact: false })).toBeVisible()

  await expect(page.getByText('JVM 与 GC').first()).toBeVisible()
  await expect(page.getByText('67%').first()).toBeVisible()
  await expect(page.getByText('深度不足').first()).toBeVisible()
  await expect(page.locator('.metric-row').filter({ hasText: '技术面' })).toBeVisible()
  await expect(page.getByText('33%').first()).toBeVisible()
  await expect(page.locator('.company strong').filter({ hasText: '字节跳动' })).toBeVisible()
  await expect(page.getByText('讲讲 JVM 内存模型')).toBeVisible()
  await expect(page.getByText('面试邀约率 60%', { exact: false })).toBeVisible()
  await expect(page.getByText('resume-v3.pdf')).toBeVisible()
  await expect(page.getByText('按渠道')).toBeVisible()
  await expect(page.getByText('BOSS')).toBeVisible()
  await expect(page.getByText('企业服务')).toBeVisible()
  await expect(page.getByText('所选条件下没有面试记录。')).toBeVisible()
  await expect(page.getByText('验证率 33%', { exact: false })).toBeVisible()
  await expect(page.getByText('补 JVM 笔记')).toBeVisible()
  await expect(page.getByText('+2')).toBeVisible()

  // AI 总结（FR5.8 可选）：成功后展示模型总结与来源
  await page.getByRole('button', { name: 'AI 总结' }).click()
  await expect.poll(() => summaryBody).toContain('"days":90')
  await expect(page.getByText('整体表现稳定，优先补 JVM 深度。')).toBeVisible()
  await expect(page.getByText('模型：corp / flash')).toBeVisible()

  await page.getByLabel('统计窗口').selectOption('30')
  await expect.poll(() => lastQuery).toContain('days=30')
})

test('求职洞察：无已确认复盘时引导去新建复盘', async ({ page }) => {
  await page.route('**/api/companies?*', (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 100 })),
  )
  await page.route('**/api/insights/interview-events?*', (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 8 })),
  )
  await page.route('**/api/insights?*', (route) =>
    route.fulfill(
      ok({
        ...overview,
        sample: { applications: 0, interviews: 0, reviews: 0, confirmedReviews: 0, offers: 0, minimum: 5, sufficient: false },
        summary: {
          content: '暂无已确认的复盘分析；完成一场复盘并确认分析后，这里会出现跨场归因。',
          basedOn: 'LOCAL_AGGREGATION',
        },
        topics: [],
        lossTags: [],
        roundPassRates: [],
        companyProfiles: [],
        topQuestions: [],
        funnel: { applications: 0, interviews: 0, offers: 0, rejected: 0, withdrawn: 0, interviewRate: null, offerRate: null },
        resumeFunnel: [],
        improvements: { total: 0, todo: 0, inProgress: 0, verified: 0, dropped: 0, verificationRate: null, staleActions: [] },
        trend: { windowDays: 90, currentReviews: 0, previousReviews: 0, sufficient: false, lossTagDeltas: [], passRateDelta: null },
      }),
    ),
  )
  await page.route(/\/api\/review-sessions(\?|$)/, (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 20 })),
  )
  await page.route(/\/api\/job-applications\?/, (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 100 })),
  )

  await page.goto('/insights')
  await expect(page.getByText('还没有可归因的复盘')).toBeVisible()
  await page.getByRole('button', { name: '去新建复盘' }).click()
  await expect(page).toHaveURL(/\/reviews$/)
})
