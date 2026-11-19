/**
 * 模拟面试纯函数：状态文案、下一题与进度、分数色调。
 * 抽离自页面，便于单测覆盖而不依赖组件渲染。
 */
import type { InterviewQuestion, InterviewSessionStatus } from '@/api/interview'

export const SESSION_STATUS_TEXT: Record<InterviewSessionStatus, string> = {
  PREPARING: '出题中',
  IN_PROGRESS: '进行中',
  GENERATING: '追问生成中',
  SUBMITTED: '评估中',
  COMPLETED: '已完成',
  FAILED: '出题失败',
}

export const SESSION_STATUS_TONE: Record<InterviewSessionStatus, string> = {
  PREPARING: 'primary',
  IN_PROGRESS: 'success',
  GENERATING: 'warning',
  SUBMITTED: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
}

export const DIFFICULTY_TEXT: Record<string, string> = {
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}

export const QUESTION_TYPE_TEXT: Record<string, string> = {
  MAIN: '主问题',
  FOLLOW_UP: '追问',
}

/** 下一道待作答题目：跳过已答与已跳过。 */
export function nextQuestion(
  questions: InterviewQuestion[] | null | undefined,
): InterviewQuestion | undefined {
  return (questions ?? []).find((item) => item.answerStatus === 'UNANSWERED')
}

/** 进度文案统一由已答数 + 当前题目总数生成。 */
export function progressText(answeredCount: number, total: number): string {
  return `已答 ${answeredCount} / 共 ${total} 题`
}

export function scoreTone(score: number | null | undefined): 'high' | 'mid' | 'low' {
  if (score == null) {
    return 'low'
  }
  if (score >= 80) {
    return 'high'
  }
  return score >= 60 ? 'mid' : 'low'
}

export function evaluationStatusText(status: string, retryCount = 0): string {
  switch (status) {
    case 'PENDING':
      return '评估排队中'
    case 'PROCESSING':
      return '评估中'
    case 'COMPLETED':
      return '评估完成'
    case 'FAILED':
      return `评估失败（已重试 ${retryCount} 次）`
    default:
      return status
  }
}
