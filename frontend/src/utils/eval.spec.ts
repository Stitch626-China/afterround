import { describe, expect, it } from 'vitest'
import {
  caseStatusLabel,
  changeLabel,
  failureCounts,
  formatDuration,
  formatRate,
  formatTokens,
  isTerminal,
  progressPercent,
  runStatusLabel,
} from './eval'

describe('eval 纯函数', () => {
  it('进度百分比按已完成 / 总数，总数 0 时为 0', () => {
    expect(progressPercent({ completedCases: 6, totalCases: 10 })).toBe(60)
    expect(progressPercent({ completedCases: 0, totalCases: 0 })).toBe(0)
    expect(progressPercent({ completedCases: 11, totalCases: 10 })).toBe(100)
  })

  it('终态判断与状态文案', () => {
    expect(isTerminal('PROCESSING')).toBe(false)
    expect(isTerminal('COMPLETED')).toBe(true)
    expect(runStatusLabel('FAILED')).toBe('失败')
    expect(caseStatusLabel('ERROR')).toBe('执行错误')
    expect(caseStatusLabel('FAIL')).toBe('未通过')
  })

  it('耗时格式化', () => {
    expect(formatDuration(null)).toBe('—')
    expect(formatDuration(320)).toBe('320ms')
    expect(formatDuration(2500)).toBe('2.5s')
    expect(formatDuration(45_000)).toBe('45s')
    expect(formatDuration(125_000)).toBe('2m 5s')
  })

  it('token / 通过率缺失时显示短横线', () => {
    expect(formatTokens(null)).toBe('—')
    expect(formatTokens(3000)).toBe('3,000')
    expect(formatRate(null)).toBe('—')
    expect(formatRate(0.6667)).toBe('66.7%')
  })

  it('质量失败与执行错误分开计数', () => {
    const cases = [
      { status: 'FAIL' },
      { status: 'ERROR' },
      { status: 'PASS' },
      { status: 'SKIPPED' },
    ] as never
    expect(failureCounts(cases)).toEqual({ failed: 1, errors: 1 })
  })

  it('对比变化文案：已知值中文，未知值原样返回', () => {
    expect(changeLabel('FIXED')).toBe('修复')
    expect(changeLabel('REGRESSED')).toBe('新增失败')
    expect(changeLabel('SCORE_DRIFT')).toBe('分数漂移')
    expect(changeLabel('TAG_CHANGED')).toBe('标签变化')
    expect(changeLabel('UNKNOWN')).toBe('UNKNOWN')
  })
})
