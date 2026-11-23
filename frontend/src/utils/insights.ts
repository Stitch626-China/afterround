/**
 * 洞察页纯函数：百分比、条形宽度、样本提示与趋势差值文案。
 */
import type { InsightSample } from '@/api/insights'

export const INSIGHT_WINDOWS = [30, 90, 180, 365]

/** 比例 → 百分比文案；null（分母为 0）显示占位符。 */
export function pct(value: number | null | undefined): string {
  return value == null ? '—' : `${Math.round(value * 100)}%`
}

/** 比例 → 0~100 的 CSS 宽度，防止异常值撑破布局。 */
export function barWidth(value: number | null | undefined): string {
  const bounded = Math.max(0, Math.min(1, value ?? 0))
  return `${Math.round(bounded * 100)}%`
}

export function sampleText(sample: InsightSample): string {
  return `已确认复盘 ${sample.confirmedReviews} 份 · 门槛 ${sample.minimum} 份`
}

export function sampleHint(sample: InsightSample): string {
  return sample.sufficient
    ? '样本量已达到门槛，可参考趋势结论；仍需结合具体面试上下文。'
    : `样本不足（N < ${sample.minimum}）：只展示原始统计，不输出因果结论。`
}

/** 趋势差值：正数带 +，0 显示 0，便于表格对齐。 */
export function deltaText(delta: number | null | undefined): string {
  if (delta == null) {
    return '—'
  }
  return delta > 0 ? `+${delta}` : String(delta)
}
