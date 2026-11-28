/**
 * 提示词工作台接口（契约 1.25.0；提示词 8 端点自 1.22.0 引入）。
 * 类型由契约生成，禁止手写数据结构。
 */
import type { components } from './schema'
import { del, get, post, put } from './http'

export type PromptSummary = components['schemas']['PromptSummary']
export type PromptDetail = components['schemas']['PromptDetail']
export type PromptVersionSummary = components['schemas']['PromptVersionSummary']
export type PromptVersionDetail = components['schemas']['PromptVersionDetail']
export type PromptValidation = components['schemas']['PromptValidation']
export type SavePromptDraftRequest = components['schemas']['SavePromptDraftRequest']
export type PublishPromptRequest = components['schemas']['PublishPromptRequest']

/** 分页结构由契约派生，仅把 items 收紧为必填。 */
export type PromptVersionPage = NonNullable<components['schemas']['ResultPromptVersionPage']['data']> & {
  items: PromptVersionSummary[]
}

export function listPrompts(): Promise<PromptSummary[]> {
  return get<PromptSummary[]>('/llm/prompts')
}

export function getPrompt(promptKey: string): Promise<PromptDetail> {
  return get<PromptDetail>(`/llm/prompts/${promptKey}`)
}

export function savePromptDraft(promptKey: string, payload: SavePromptDraftRequest): Promise<PromptDetail> {
  return put<PromptDetail>(`/llm/prompts/${promptKey}/draft`, payload)
}

export function discardPromptDraft(promptKey: string): Promise<void> {
  return del<void>(`/llm/prompts/${promptKey}/draft`)
}

export function publishPrompt(promptKey: string, payload: PublishPromptRequest = {}): Promise<PromptDetail> {
  return post<PromptDetail>(`/llm/prompts/${promptKey}/publish`, payload)
}

export function listPromptVersions(
  promptKey: string,
  page = 1,
  size = 20,
): Promise<PromptVersionPage> {
  return get<PromptVersionPage>(`/llm/prompts/${promptKey}/versions?page=${page}&size=${size}`)
}

export function getPromptVersion(promptKey: string, versionNo: number): Promise<PromptVersionDetail> {
  return get<PromptVersionDetail>(`/llm/prompts/${promptKey}/versions/${versionNo}`)
}

export function activatePromptVersion(promptKey: string, versionNo: number): Promise<PromptDetail> {
  return post<PromptDetail>(`/llm/prompts/${promptKey}/versions/${versionNo}/activate`)
}
