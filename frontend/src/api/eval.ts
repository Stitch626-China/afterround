/**
 * 评测运行接口（contracts/openapi.yaml 1.23.0）。
 * 类型由契约生成，禁止手写数据结构。
 */
import type { components } from './schema'
import { get, post } from './http'

export type EvalSceneSummary = components['schemas']['EvalSceneSummary']
export type EvalRunSummary = components['schemas']['EvalRunSummary']
export type EvalRunDetail = components['schemas']['EvalRunDetail']
export type EvalCaseResult = components['schemas']['EvalCaseResult']
export type EvalMetrics = components['schemas']['EvalMetrics']
export type EvalAssertionResult = components['schemas']['EvalAssertionResult']
export type CreateEvalRunRequest = components['schemas']['CreateEvalRunRequest']

/** 分页结构由契约派生，仅把 items 收紧为必填。 */
export type EvalRunPage = NonNullable<components['schemas']['ResultEvalRunPage']['data']> & {
  items: EvalRunSummary[]
}

export function listEvalScenes(): Promise<EvalSceneSummary[]> {
  return get<EvalSceneSummary[]>('/llm/eval/scenes')
}

export function createEvalRun(payload: CreateEvalRunRequest): Promise<EvalRunDetail> {
  return post<EvalRunDetail>('/llm/eval/runs', payload)
}

export function listEvalRuns(page = 1, size = 20): Promise<EvalRunPage> {
  return get<EvalRunPage>(`/llm/eval/runs?page=${page}&size=${size}`)
}

export function getEvalRun(runId: number): Promise<EvalRunDetail> {
  return get<EvalRunDetail>(`/llm/eval/runs/${runId}`)
}

export function cancelEvalRun(runId: number): Promise<EvalRunDetail> {
  return post<EvalRunDetail>(`/llm/eval/runs/${runId}/cancel`)
}
