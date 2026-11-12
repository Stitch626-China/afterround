/**
 * SSE 帧解析：Spring 的 ServerSentEvent 以 `data:{json}\n\n` 返回。
 * 纯函数便于单测，跨 chunk 的不完整帧留在 rest 中等待下一次拼接。
 */
export function extractSseEvents(buffer: string): { events: string[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const frames = normalized.split('\n\n')
  const rest = frames.pop() ?? ''
  const events: string[] = []

  for (const frame of frames) {
    for (const line of frame.split('\n')) {
      const trimmed = line.trimStart()
      if (!trimmed.startsWith('data:')) {
        continue
      }
      const data = trimmed.slice(5).trim()
      if (data) {
        events.push(data)
      }
    }
  }
  return { events, rest }
}
