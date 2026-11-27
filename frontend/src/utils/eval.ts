/**
 * 评测面板纯函数：进度、状态文案、耗时/成本格式化与质量失败 / 执行错误区分。
 * 口径：FAIL 为质量失败，ERROR 为执行错误，两者分开计数。
 */
import type { EvalCaseResult, EvalRunSummary } from '@/api/eval'

const TERMINAL = ['COMPLETED', 'FAILED', 'CANCELLED']

const RUN_LABELS: Record<string, string> = {
  PENDING: '排队中',
  PROCESSING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

const CASE_LABELS: Record<string, string> = {
  PENDING: '待执行',
  PASS: '通过',
  FAIL: '未通过',
  GRACEFUL: '优雅降级',
  ERROR: '执行错误',
  SKIPPED: '已跳过',
}

const CHANGE_LABELS: Record<string, string> = {
  FIXED: '修复',
  REGRESSED: '新增失败',
  SCORE_DRIFT: '分数漂移',
  TAG_CHANGED: '标签变化',
  UNCHANGED: '无变化',
}

export function isTerminal(status: string): boolean {
  return TERMINAL.includes(status)
}

/** 进度百分比 0~100；总数为 0 时返回 0。 */
export function progressPercent(run: Pick<EvalRunSummary, 'completedCases' | 'totalCases'>): number {
  if (!run.totalCases) {
    return 0
  }
  return Math.min(100, Math.round((run.completedCases / run.totalCases) * 100))
}

export function runStatusLabel(status: string): string {
  return RUN_LABELS[status] ?? status
}

export function caseStatusLabel(status: string): string {
  return CASE_LABELS[status] ?? status
}

/** 对比变化文案：FIXED / REGRESSED / SCORE_DRIFT / TAG_CHANGED。 */
export function changeLabel(change: string): string {
  return CHANGE_LABELS[change] ?? change
}

/** 耗时：ms / s / m s，缺失显示短横线。 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) {
    return '—'
  }
  if (ms < 1000) {
    return `${Math.round(ms)}ms`
  }
  const seconds = ms / 1000
  if (seconds < 60) {
    return `${seconds.toFixed(seconds < 10 ? 1 : 0)}s`
  }
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds % 60)
  return rest ? `${minutes}m ${rest}s` : `${minutes}m`
}

export function formatTokens(value: number | null | undefined): string {
  return value == null ? '—' : value.toLocaleString('zh-CN')
}

/** 通过率 0~1 → 百分比；无数据显示短横线，不伪造 0%。 */
export function formatRate(value: number | null | undefined): string {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`
}

export function failureCounts(cases: EvalCaseResult[]): { failed: number; errors: number } {
  return {
    failed: cases.filter((item) => item.status === 'FAIL').length,
    errors: cases.filter((item) => item.status === 'ERROR').length,
  }
}
