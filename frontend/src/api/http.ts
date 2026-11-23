/**
 * 后端 HTTP 封装。
 *
 * 约定（见 contracts/openapi.yaml）：
 * - 所有 JSON 响应统一为 Result<T>：{ code, message, data, traceId }
 * - code === 0 表示成功，非 0 为业务错误码，同时 HTTP 状态为 4xx/5xx
 * - traceId 由后端写入响应头 X-Trace-Id 并回填到响应体
 *
 * 业务数据结构请从 '@/api/schema'（由契约生成）取，禁止手写接口类型。
 */

/** 后端统一响应包装。 */
interface ResultEnvelope<T> {
  code: number
  message: string
  data?: T
  traceId?: string
}

/** 业务错误：携带后端错误码与 traceId，便于排查。 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

export const BASE_URL = '/api'

/**
 * 发起请求并解包 Result<T>，成功时直接返回 data。
 *
 * @param path 形如 '/resumes'、'/resumes/1'（不含 /api 前缀）
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const isForm = typeof FormData !== 'undefined' && init.body instanceof FormData
  let response: Response
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      headers: {
        ...(isForm ? {} : { 'Content-Type': 'application/json' }),
        ...(init.headers ?? {}),
      },
      ...init,
    })
  } catch {
    throw new ApiError(-1, '无法连接后端服务，请确认后端已启动（默认 8080）')
  }

  const traceId = response.headers.get('X-Trace-Id') ?? undefined

  // 非 JSON（如后端未启动、代理 500）时给友好提示，避免把解析异常抛给用户
  const text = await response.text()
  let envelope: ResultEnvelope<T> | null = null
  try {
    envelope = text ? (JSON.parse(text) as ResultEnvelope<T>) : null
  } catch {
    envelope = null
  }

  if (!envelope) {
    throw new ApiError(
      response.status || -1,
      response.ok ? '响应格式异常，请稍后重试' : `请求失败（HTTP ${response.status}）`,
      traceId,
    )
  }

  if (envelope.code !== 0) {
    throw new ApiError(envelope.code, envelope.message, envelope.traceId ?? traceId)
  }
  return envelope.data as T
}

/** 便捷方法：GET */
export function get<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'GET' })
}

/** 便捷方法：POST（JSON body） */
export function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

/** 便捷方法：PUT（JSON body） */
export function put<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'PUT',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

/** 便捷方法：PATCH（JSON body） */
export function patch<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'PATCH',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

/** 便捷方法：DELETE */
export function del<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'DELETE' })
}

/** 便捷方法：POST multipart（浏览器自动设置 boundary，不能手动指定 Content-Type）。 */
export function postForm<T>(path: string, form: FormData): Promise<T> {
  return request<T>(path, { method: 'POST', body: form })
}
