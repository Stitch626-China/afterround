import { describe, expect, it } from 'vitest'
import type { InsightSample } from '@/api/insights'
import { barWidth, deltaText, pct, sampleHint, sampleText } from './insights'

const sample = (overrides: Partial<InsightSample> = {}): InsightSample => ({
  applications: 10,
  interviews: 4,
  reviews: 4,
  confirmedReviews: 4,
  offers: 1,
  minimum: 5,
  sufficient: false,
  ...overrides,
})

describe('insights utils', () => {
  it('比例转百分比与条形宽度', () => {
    expect(pct(0.6667)).toBe('67%')
    expect(pct(0)).toBe('0%')
    expect(pct(null)).toBe('—')
    expect(barWidth(0.5)).toBe('50%')
    expect(barWidth(null)).toBe('0%')
    expect(barWidth(1.5)).toBe('100%')
  })

  it('样本文案区分充足与不足', () => {
    expect(sampleText(sample())).toBe('已确认复盘 4 份 · 门槛 5 份')
    expect(sampleHint(sample())).toContain('样本不足')
    expect(sampleHint(sample({ sufficient: true, confirmedReviews: 6 }))).toContain('已达到门槛')
  })

  it('趋势差值带符号且空值占位', () => {
    expect(deltaText(3)).toBe('+3')
    expect(deltaText(-2)).toBe('-2')
    expect(deltaText(0)).toBe('0')
    expect(deltaText(null)).toBe('—')
  })
})
