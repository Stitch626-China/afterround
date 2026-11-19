/**
 * Offer 表单纯函数：DTO ↔ 表单转换、空值语义与展示文案。
 * 契约约定：更新时不传不修改、显式 null 清空，因此空输入统一转为 null。
 */
import type {
  CreateOfferRequest,
  Offer,
  OfferDetail,
  OfferExtraction,
  OfferGroup,
} from '@/api/offers'

export type WeekendChoice = 'UNSET' | 'YES' | 'NO'

export interface OfferFormState {
  companyId: string
  jobPositionId: string
  /** 数字输入框 v-model 会返回 number，这里兼容两种类型。 */
  monthlySalary: string | number
  baseSalary: string | number
  performanceRatio: string | number
  socialInsuranceBase: string
  socialInsuranceRatio: string
  housingFundBase: string
  housingFundRatio: string
  twoDayWeekend: WeekendChoice
  workHours: string
  benefits: string
  allowances: string
  annualBonus: string
  quarterlyBonus: string
  remark: string
  group: OfferGroup
}

export function emptyOfferForm(): OfferFormState {
  return {
    companyId: '',
    jobPositionId: '',
    monthlySalary: '',
    baseSalary: '',
    performanceRatio: '',
    socialInsuranceBase: '',
    socialInsuranceRatio: '',
    housingFundBase: '',
    housingFundRatio: '',
    twoDayWeekend: 'UNSET',
    workHours: '',
    benefits: '',
    allowances: '',
    annualBonus: '',
    quarterlyBonus: '',
    remark: '',
    group: 'KEEP',
  }
}

export function offerToForm(offer: Offer | OfferDetail): OfferFormState {
  return {
    companyId: text(offer.companyId),
    jobPositionId: text(offer.jobPositionId),
    monthlySalary: text(offer.monthlySalary),
    baseSalary: text(offer.baseSalary),
    performanceRatio: text(offer.performanceRatio),
    socialInsuranceBase: offer.socialInsuranceBase ?? '',
    socialInsuranceRatio: offer.socialInsuranceRatio ?? '',
    housingFundBase: offer.housingFundBase ?? '',
    housingFundRatio: offer.housingFundRatio ?? '',
    twoDayWeekend:
      offer.twoDayWeekend === true ? 'YES' : offer.twoDayWeekend === false ? 'NO' : 'UNSET',
    workHours: offer.workHours ?? '',
    benefits: (offer.benefits ?? []).join('、'),
    allowances: (offer.allowances ?? []).join('、'),
    annualBonus: offer.annualBonus ?? '',
    quarterlyBonus: offer.quarterlyBonus ?? '',
    remark: offer.remark ?? '',
    group: offer.group,
  }
}

/** 提取结果只覆盖可编辑字段，公司与岗位仍需用户确认（避免自动合并）。 */
export function extractionToForm(
  extraction: OfferExtraction,
  base: OfferFormState = emptyOfferForm(),
): OfferFormState {
  return {
    ...base,
    monthlySalary: text(extraction.monthlySalary),
    baseSalary: text(extraction.baseSalary),
    performanceRatio: text(extraction.performanceRatio),
    socialInsuranceBase: extraction.socialInsuranceBase ?? '',
    socialInsuranceRatio: extraction.socialInsuranceRatio ?? '',
    housingFundBase: extraction.housingFundBase ?? '',
    housingFundRatio: extraction.housingFundRatio ?? '',
    twoDayWeekend:
      extraction.twoDayWeekend === true
        ? 'YES'
        : extraction.twoDayWeekend === false
          ? 'NO'
          : 'UNSET',
    workHours: extraction.workHours ?? '',
    benefits: (extraction.benefits ?? []).join('、'),
    allowances: (extraction.allowances ?? []).join('、'),
    annualBonus: extraction.annualBonus ?? '',
    quarterlyBonus: extraction.quarterlyBonus ?? '',
    remark: extraction.remark ?? '',
  }
}

export function formToPayload(form: OfferFormState): CreateOfferRequest {
  return {
    companyId: intOrNull(form.companyId),
    jobPositionId: intOrNull(form.jobPositionId),
    monthlySalary: intOrNull(form.monthlySalary),
    baseSalary: intOrNull(form.baseSalary),
    performanceRatio: numberOrNull(form.performanceRatio),
    socialInsuranceBase: textOrNull(form.socialInsuranceBase),
    socialInsuranceRatio: textOrNull(form.socialInsuranceRatio),
    housingFundBase: textOrNull(form.housingFundBase),
    housingFundRatio: textOrNull(form.housingFundRatio),
    twoDayWeekend:
      form.twoDayWeekend === 'UNSET' ? null : form.twoDayWeekend === 'YES',
    workHours: textOrNull(form.workHours),
    benefits: splitList(form.benefits),
    allowances: splitList(form.allowances),
    annualBonus: textOrNull(form.annualBonus),
    quarterlyBonus: textOrNull(form.quarterlyBonus),
    remark: textOrNull(form.remark),
    group: form.group,
  }
}

/** 月薪 / 底薪 / 绩效的统一展示。 */
export function salaryText(
  offer: Pick<Offer, 'monthlySalary' | 'baseSalary' | 'performanceRatio'>,
): string {
  const parts: string[] = []
  if (offer.monthlySalary != null) {
    parts.push(`月薪 ${offer.monthlySalary} 元`)
  }
  if (offer.baseSalary != null) {
    parts.push(`底薪 ${offer.baseSalary} 元`)
  }
  if (offer.performanceRatio != null) {
    parts.push(`绩效 ${offer.performanceRatio}%`)
  }
  return parts.length ? parts.join(' · ') : '薪资未填写'
}

export function groupText(group: OfferGroup): string {
  return group === 'KEEP' ? '保留' : '淘汰'
}

/** 中文逗号 / 顿号 / 换行均可分隔。 */
export function splitList(value: string): string[] {
  return value
    .split(/[,，、\n]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

function text(value: number | null | undefined): string {
  return value == null ? '' : String(value)
}

function textOrNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function numberOrNull(value: string | number): number | null {
  const trimmed = String(value).trim()
  if (!trimmed) {
    return null
  }
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

function intOrNull(value: string | number): number | null {
  const parsed = numberOrNull(value)
  return parsed == null ? null : Math.round(parsed)
}
