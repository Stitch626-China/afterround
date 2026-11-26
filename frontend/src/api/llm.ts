/**
 * 模型服务接口（类型由 contracts/openapi.yaml 生成，禁止手写数据结构）。
 */
import type { components } from './schema'
import { del, get, post, put } from './http'

export type LlmProvider = components['schemas']['LlmProvider']
export type LlmModel = components['schemas']['LlmModel']
export type CreateProviderRequest = components['schemas']['CreateProviderRequest']
export type UpdateProviderRequest = components['schemas']['UpdateProviderRequest']
export type CreateModelRequest = components['schemas']['CreateModelRequest']
export type UpdateModelRequest = components['schemas']['UpdateModelRequest']
export type ConnectivityTest = components['schemas']['ConnectivityTest']
export type UpstreamModelList = components['schemas']['UpstreamModelList']
export type GlobalSetting = components['schemas']['GlobalSetting']
export type UpdateGlobalSettingRequest = components['schemas']['UpdateGlobalSettingRequest']
export type LlmUsage = components['schemas']['LlmUsageResponse']

export function listProviders(): Promise<LlmProvider[]> {
  return get<LlmProvider[]>('/llm/providers')
}

export function createProvider(payload: CreateProviderRequest): Promise<LlmProvider> {
  return post<LlmProvider>('/llm/providers', payload)
}

export function updateProvider(key: string, payload: UpdateProviderRequest): Promise<LlmProvider> {
  return put<LlmProvider>(`/llm/providers/${key}`, payload)
}

export function deleteProvider(key: string): Promise<void> {
  return del<void>(`/llm/providers/${key}`)
}

export function testProvider(key: string, modelName?: string): Promise<ConnectivityTest> {
  return post<ConnectivityTest>(`/llm/providers/${key}/test`, modelName ? { modelName } : {})
}

export function listUpstreamModels(key: string): Promise<UpstreamModelList> {
  return get<UpstreamModelList>(`/llm/providers/${key}/models`)
}

export function createModel(key: string, payload: CreateModelRequest): Promise<LlmModel> {
  return post<LlmModel>(`/llm/providers/${key}/models`, payload)
}

export function updateModel(key: string, modelName: string, payload: UpdateModelRequest): Promise<LlmModel> {
  return put<LlmModel>(`/llm/providers/${key}/models/${encodeURIComponent(modelName)}`, payload)
}

export function deleteModel(key: string, modelName: string): Promise<void> {
  return del<void>(`/llm/providers/${key}/models/${encodeURIComponent(modelName)}`)
}

export function getGlobalSetting(): Promise<GlobalSetting> {
  return get<GlobalSetting>('/llm/global')
}

export function updateGlobalSetting(payload: UpdateGlobalSettingRequest): Promise<GlobalSetting> {
  return put<GlobalSetting>('/llm/global', payload)
}

/** token 成本统计（NFR6）：days 取值 7~365，后端默认 30。 */
export function getLlmUsage(days = 30): Promise<LlmUsage> {
  return get<LlmUsage>(`/llm/usage?days=${days}`)
}
