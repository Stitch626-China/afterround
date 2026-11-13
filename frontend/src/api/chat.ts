/**
 * 对话会话接口（契约 contracts/openapi.yaml：7 个端点）。
 * 发送消息走 SSE 流式，单独用 fetch + ReadableStream 处理。
 */
import type { components } from './schema'
import { ApiError, BASE_URL, del, get, patch, post } from './http'
import { extractSseEvents } from './sse'

export type ChatSession = components['schemas']['ChatSession']
export type ChatMessage = components['schemas']['ChatMessage']
export type ChatStreamEvent = components['schemas']['ChatStreamEvent']
export type CreateChatSessionRequest = components['schemas']['CreateChatSessionRequest']
export type SwitchModelRequest = components['schemas']['SwitchModelRequest']

export interface ChatSessionPage {
  items: ChatSession[]
  total: number
  page: number
  size: number
}

export function listChatSessions(page = 1, size = 20): Promise<ChatSessionPage> {
  return get<ChatSessionPage>(`/chat/sessions?page=${page}&size=${size}`)
}

export function createChatSession(payload: CreateChatSessionRequest = {}): Promise<ChatSession> {
  return post<ChatSession>('/chat/sessions', payload)
}

export function getChatSession(id: number): Promise<ChatSession> {
  return get<ChatSession>(`/chat/sessions/${id}`)
}

export function deleteChatSession(id: number): Promise<void> {
  return del<void>(`/chat/sessions/${id}`)
}

export function switchChatModel(id: number, payload: SwitchModelRequest): Promise<ChatSession> {
  return patch<ChatSession>(`/chat/sessions/${id}/model`, payload)
}

export function listChatMessages(id: number): Promise<ChatMessage[]> {
  return get<ChatMessage[]>(`/chat/sessions/${id}/messages`)
}

/** SSE 发送消息：逐事件回调，流结束后 resolve；HTTP/网络错误抛 ApiError。 */
export async function streamChatMessage(
  sessionId: number,
  content: string,
  onEvent: (event: ChatStreamEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(`${BASE_URL}/chat/sessions/${sessionId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
      signal,
    })
  } catch {
    throw new ApiError(-1, '无法连接后端服务，请确认后端已启动（默认 8080）')
  }

  if (!response.ok || !response.body) {
    const text = await response.text()
    let envelope: { code?: number; message?: string } | null = null
    try {
      envelope = text ? JSON.parse(text) : null
    } catch {
      envelope = null
    }
    throw new ApiError(
      envelope?.code ?? response.status,
      envelope?.message ?? `发送失败（HTTP ${response.status}）`,
    )
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const emit = (frames: string[]): void => {
    for (const frame of frames) {
      try {
        onEvent(JSON.parse(frame) as ChatStreamEvent)
      } catch {
        // 忽略无法解析的帧，不中断整条流
      }
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const parsed = extractSseEvents(buffer)
    buffer = parsed.rest
    emit(parsed.events)
  }

  // 收尾：兼容末尾没有空行的最后一帧
  const tail = extractSseEvents(`${buffer}\n\n`)
  emit(tail.events)
}
