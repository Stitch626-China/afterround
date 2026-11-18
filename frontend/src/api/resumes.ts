/**
 * 简历中心接口（类型由 contracts/openapi.yaml 生成，禁止手写数据结构）。
 */
import type { components } from './schema'
import { del, get, post, put } from './http'

export type ResumeListItem = components['schemas']['ResumeListItem']
export type ResumeDetail = components['schemas']['ResumeDetail']
export type ResumeUpload = components['schemas']['ResumeUpload']
export type ResumeAnalysisStatus = components['schemas']['ResumeAnalysisStatus']
export type ResumeAnalysisReport = components['schemas']['ResumeAnalysisReport']
export type AnalysisDimension = components['schemas']['AnalysisDimension']
export type CareerDirection = components['schemas']['CareerDirection']
export type QuoteItem = components['schemas']['QuoteItem']
export type SuggestionItem = components['schemas']['SuggestionItem']
export type ResumeBasicInfo = components['schemas']['ResumeBasicInfo']
export type UpdateSalaryRequest = components['schemas']['UpdateSalaryRequest']
export type ResumeComparison = components['schemas']['ResumeComparison']
export type ComparisonSide = components['schemas']['ComparisonSide']
export type DimensionDelta = components['schemas']['DimensionDelta']
export type ComparisonSummary = components['schemas']['ComparisonSummary']

/** 分页结构由契约派生，仅把 items 收紧为必填避免页面重复判空。 */
export type ResumePage = NonNullable<components['schemas']['ResultResumePage']['data']> & {
  items: ResumeListItem[]
}

export function listResumes(page = 1, size = 20): Promise<ResumePage> {
  return get<ResumePage>(`/resumes?page=${page}&size=${size}`)
}

export function getResume(id: number): Promise<ResumeDetail> {
  return get<ResumeDetail>(`/resumes/${id}`)
}

export function deleteResume(id: number): Promise<void> {
  return del<void>(`/resumes/${id}`)
}

export function triggerAnalysis(id: number): Promise<ResumeAnalysisStatus> {
  return post<ResumeAnalysisStatus>(`/resumes/${id}/analyze`)
}

export function getAnalysis(id: number): Promise<ResumeAnalysisStatus> {
  return get<ResumeAnalysisStatus>(`/resumes/${id}/analysis`)
}

export function updateResumeSalary(id: number, payload: UpdateSalaryRequest): Promise<ResumeAnalysisStatus> {
  return put<ResumeAnalysisStatus>(`/resumes/${id}/salary`, payload)
}

/** 简历版本对比（F7）：两份都必须分析完成；同步返回，不落库。 */
export function compareResumes(baseResumeId: number, targetResumeId: number): Promise<ResumeComparison> {
  return post<ResumeComparison>('/resume-comparisons', { baseResumeId, targetResumeId })
}

/** 上传简历：file 与 text 二选一。 */
export async function uploadResume(input: { file?: File; text?: string }): Promise<ResumeUpload> {
  const form = new FormData()
  if (input.file) {
    form.append('file', input.file)
  }
  if (input.text) {
    form.append('text', input.text)
  }
  const response = await fetch('/api/resumes', { method: 'POST', body: form })
  const text = await response.text()
  let envelope: { code: number; message?: string; data?: unknown } | null = null
  try {
    envelope = text ? JSON.parse(text) : null
  } catch {
    envelope = null
  }
  if (!envelope || envelope.code !== 0) {
    throw new Error(envelope?.message ?? `上传失败（HTTP ${response.status}）`)
  }
  return envelope.data as ResumeUpload
}
