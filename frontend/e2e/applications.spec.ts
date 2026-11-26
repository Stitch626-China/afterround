import { expect, test } from '@playwright/test'

/**
 * 投递档案 E2E（接口全 mock）：时间线内的面试轮次可编辑、无复盘可删除。
 */

const ok = (data: unknown) => ({ json: { code: 0, message: 'ok', data } })
const now = '2026-11-26T10:00:00Z'

const application = {
  id: 5,
  jobPositionId: 3,
  jobTitle: 'Java 后端',
  companyId: 2,
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

let event = {
  id: 9,
  applicationId: 5,
  companyId: 2,
  companyName: '字节跳动',
  jobPositionId: 3,
  jobTitle: 'Java 后端',
  roundNo: 1,
  roundType: 'TECH',
  interviewerRole: '技术负责人',
  format: 'VIDEO',
  interviewTime: now,
  result: 'PENDING',
  notes: null,
  reviewSessionId: null,
  reviewStatus: null,
  createdAt: now,
  updatedAt: now,
}

test('投递时间线：编辑并删除无复盘的面试轮次', async ({ page }) => {
  let patchBody = ''
  let deleteCalled = false
  event = { ...event, interviewerRole: '技术负责人', notes: null }

  await page.route(/\/api\/job-applications\?/, (route) =>
    route.fulfill(ok({ items: [application], total: 1, page: 1, size: 20 })),
  )
  await page.route('**/api/job-applications/5/timeline', (route) =>
    route.fulfill(ok({ application, events: [event], reviews: [], offer: null, actions: [] })),
  )
  await page.route('**/api/interview-events/9', (route) => {
    if (route.request().method() === 'PATCH') {
      patchBody = route.request().postData() ?? ''
      event = { ...event, ...JSON.parse(patchBody), updatedAt: now }
      return route.fulfill(ok(event))
    }
    deleteCalled = true
    return route.fulfill(ok(null))
  })

  await page.goto('/applications')
  await page.getByRole('button', { name: '时间线' }).click()

  const timeline = page.locator('.timeline')
  await expect(timeline.getByText('技术负责人')).toBeVisible()
  await timeline.getByRole('button', { name: '编辑' }).click()
  await page.getByPlaceholder('如 技术负责人').fill('HRBP')
  await timeline.getByRole('button', { name: '保存', exact: true }).click()

  await expect.poll(() => patchBody).toContain('HRBP')
  await expect(page.getByText('面试信息已更新')).toBeVisible()
  await expect(timeline.getByText('HRBP')).toBeVisible()

  await timeline.getByRole('button', { name: '删除', exact: true }).click()
  await timeline.getByRole('button', { name: '确认删除' }).click()
  await expect.poll(() => deleteCalled).toBe(true)
})
