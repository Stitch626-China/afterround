import { describe, expect, it } from 'vitest'
import type { InterviewQuestion } from '@/api/interview'
import {
  evaluationStatusText,
  nextQuestion,
  progressText,
  scoreTone,
  SESSION_STATUS_TEXT,
} from './interview'

function question(questionIndex: number, answerStatus: InterviewQuestion['answerStatus']): InterviewQuestion {
  return {
    questionIndex,
    type: 'MAIN',
    content: `题目 ${questionIndex}`,
    difficulty: 'MEDIUM',
    answerStatus,
    keyPoints: [],
  }
}

describe('interview utils', () => {
  it('状态文案覆盖全部会话状态', () => {
    expect(SESSION_STATUS_TEXT.PREPARING).toBe('出题中')
    expect(SESSION_STATUS_TEXT.GENERATING).toBe('追问生成中')
    expect(SESSION_STATUS_TEXT.COMPLETED).toBe('已完成')
  })

  it('下一题跳过已答与已跳过', () => {
    const questions = [
      question(1, 'ANSWERED'),
      question(2, 'SKIPPED'),
      question(3, 'UNANSWERED'),
      question(4, 'UNANSWERED'),
    ]
    expect(nextQuestion(questions)?.questionIndex).toBe(3)
    expect(nextQuestion([])).toBeUndefined()
    expect(nextQuestion(undefined)).toBeUndefined()
  })

  it('进度文案包含已答与总数', () => {
    expect(progressText(3, 9)).toBe('已答 3 / 共 9 题')
  })

  it('分数色调按 60 / 80 分档', () => {
    expect(scoreTone(85)).toBe('high')
    expect(scoreTone(60)).toBe('mid')
    expect(scoreTone(59)).toBe('low')
    expect(scoreTone(null)).toBe('low')
  })

  it('评估状态文案带重试次数', () => {
    expect(evaluationStatusText('PROCESSING')).toBe('评估中')
    expect(evaluationStatusText('FAILED', 3)).toContain('3')
    expect(evaluationStatusText('UNKNOWN')).toBe('UNKNOWN')
  })
})
