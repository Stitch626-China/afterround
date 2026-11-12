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

export interface ResumePage {
  items: ResumeListItem[]
  total: number
  page: number
  size: number
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
  const envelope = await response.json()
  if (envelope.code !== 0) {
    throw new Error(envelope.message ?? '上传失败')
  }
  return envelope.data as ResumeUpload
}
