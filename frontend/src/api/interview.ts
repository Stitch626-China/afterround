/**
 * 模拟面试接口（F3）：方向、会话、作答、交卷与评估。
 * 类型全部来自 contracts/openapi.yaml 生成的 schema，禁止手写数据结构。
 */
import type { components } from './schema'
import { del, get, patch, post } from './http'
import { query } from './query'

export type InterviewDirection = components['schemas']['InterviewDirection']
export type InterviewQuestion = components['schemas']['InterviewQuestion']
export type InterviewSession = components['schemas']['InterviewSession']
export type InterviewSessionDetail = components['schemas']['InterviewSessionDetail']
export type InterviewSessionStatus = components['schemas']['InterviewSessionStatus']
export type InterviewEvaluation = components['schemas']['InterviewEvaluation']
export type InterviewEvaluationCategory = components['schemas']['InterviewEvaluationCategory']
export type CreateInterviewSessionRequest = components['schemas']['CreateInterviewSessionRequest']

/** 分页结构由契约派生，仅把 items 收紧为必填。 */
export type InterviewSessionPage = NonNullable<
  components['schemas']['ResultInterviewSessionPage']['data']
> & { items: InterviewSession[] }

export interface InterviewFilters {
  page?: number
  size?: number
  direction?: string
  status?: InterviewSessionStatus | ''
}

export function listInterviewDirections(): Promise<InterviewDirection[]> {
  return get<InterviewDirection[]>('/interview/directions')
}

export function listInterviewSessions(filters: InterviewFilters = {}): Promise<InterviewSessionPage> {
  return get<InterviewSessionPage>(`/interview-sessions${query({ ...filters })}`)
}

export function createInterviewSession(
  payload: CreateInterviewSessionRequest,
): Promise<InterviewSession> {
  return post<InterviewSession>('/interview-sessions', payload)
}

export function getInterviewSession(id: number): Promise<InterviewSessionDetail> {
  return get<InterviewSessionDetail>(`/interview-sessions/${id}`)
}

export function deleteInterviewSession(id: number): Promise<void> {
  return del<void>(`/interview-sessions/${id}`)
}

export function switchInterviewModel(
  id: number,
  providerKey: string,
  model: string,
): Promise<InterviewSession> {
  return patch<InterviewSession>(`/interview-sessions/${id}/model`, { providerKey, model })
}

export function retryInterviewQuestions(id: number): Promise<InterviewSession> {
  return post<InterviewSession>(`/interview-sessions/${id}/questions/retry`)
}

export function answerInterviewQuestion(
  id: number,
  questionIndex: number,
  answer: string,
): Promise<InterviewQuestion> {
  return post<InterviewQuestion>(`/interview-sessions/${id}/questions/${questionIndex}/answer`, {
    answer,
  })
}

export function skipInterviewQuestion(id: number, questionIndex: number): Promise<InterviewQuestion> {
  return post<InterviewQuestion>(`/interview-sessions/${id}/questions/${questionIndex}/skip`)
}

export function submitInterviewSession(id: number): Promise<InterviewSession> {
  return post<InterviewSession>(`/interview-sessions/${id}/submit`)
}

export function getInterviewEvaluation(id: number): Promise<InterviewEvaluation> {
  return get<InterviewEvaluation>(`/interview-sessions/${id}/evaluation`)
}
