import { describe, expect, it } from 'vitest'
import type { OfferDetail } from '@/api/offers'
import {
  emptyOfferForm,
  extractionToForm,
  formToPayload,
  groupText,
  offerToForm,
  salaryText,
  splitList,
} from './offer'

const offer: OfferDetail = {
  id: 1,
  companyId: 2,
  companyName: '字节跳动',
  jobPositionId: 3,
  applicationId: null,
  jobPositionTitle: 'Java 后端',
  monthlySalary: 30000,
  baseSalary: 24000,
  performanceRatio: 20,
  socialInsuranceBase: '12000',
  socialInsuranceRatio: '10.5%',
  housingFundBase: null,
  housingFundRatio: null,
  twoDayWeekend: true,
  workHours: '10:00-19:00',
  benefits: ['餐补', '班车'],
  allowances: [],
  annualBonus: '3 个月',
  quarterlyBonus: null,
  remark: '首选',
  group: 'KEEP',
  decisionPriorities: '薪资、稳定性',
  finalChoice: true,
  finalChoiceReason: '薪资更高且通勤更短',
  createdAt: '2026-11-19T10:00:00Z',
  updatedAt: '2026-11-19T10:00:00Z',
  analysis: null,
}

describe('offer utils', () => {
  it('DTO 转表单保留已有值与布尔三态', () => {
    const form = offerToForm(offer)
    expect(form.companyId).toBe('2')
    expect(form.monthlySalary).toBe('30000')
    expect(form.twoDayWeekend).toBe('YES')
    expect(form.benefits).toBe('餐补、班车')
    expect(form.group).toBe('KEEP')
    expect(form.decisionPriorities).toBe('薪资、稳定性')
    expect(form.finalChoice).toBe(true)
    expect(form.finalChoiceReason).toBe('薪资更高且通勤更短')
    expect(offerToForm({ ...offer, twoDayWeekend: false }).twoDayWeekend).toBe('NO')
    expect(offerToForm({ ...offer, twoDayWeekend: null }).twoDayWeekend).toBe('UNSET')
  })

  it('表单转 payload：空值显式 null，列表拆分去空', () => {
    const form = emptyOfferForm()
    form.companyId = '2'
    form.monthlySalary = '35000'
    form.performanceRatio = '15.5'
    form.twoDayWeekend = 'NO'
    form.benefits = '餐补，班车、房补'
    const payload = formToPayload(form)
    expect(payload.companyId).toBe(2)
    expect(payload.jobPositionId).toBeNull()
    expect(payload.monthlySalary).toBe(35000)
    expect(payload.performanceRatio).toBe(15.5)
    expect(payload.twoDayWeekend).toBe(false)
    expect(payload.benefits).toEqual(['餐补', '班车', '房补'])
    expect(payload.remark).toBeNull()
    expect(payload.decisionPriorities).toBeNull()
    expect(payload.finalChoice).toBe(false)
    expect(payload.finalChoiceReason).toBeNull()
  })

  it('提取结果覆盖可编辑字段但保留已选公司/岗位', () => {
    const base = emptyOfferForm()
    base.companyId = '9'
    base.jobPositionId = '11'
    const form = extractionToForm(
      { monthlySalary: 28000, twoDayWeekend: true, benefits: ['餐补'], companyName: '字节' },
      base,
    )
    expect(form.companyId).toBe('9')
    expect(form.jobPositionId).toBe('11')
    expect(form.monthlySalary).toBe('28000')
    expect(form.twoDayWeekend).toBe('YES')
    expect(form.benefits).toBe('餐补')
  })

  it('薪资与分组文案', () => {
    expect(salaryText(offer)).toBe('月薪 30000 元 · 底薪 24000 元 · 绩效 20%')
    expect(salaryText({ monthlySalary: null, baseSalary: null, performanceRatio: null }))
      .toBe('薪资未填写')
    expect(groupText('KEEP')).toBe('保留')
    expect(groupText('DROP')).toBe('淘汰')
  })

  it('列表分隔符支持中英文与换行', () => {
    expect(splitList('a,b，c、d\ne')).toEqual(['a', 'b', 'c', 'd', 'e'])
    expect(splitList('  ')).toEqual([])
  })
})
