import { describe, expect, it } from 'vitest'
import { extractSseEvents } from './sse'

describe('extractSseEvents', () => {
  it('拆分完整帧并保留未完成部分', () => {
    const { events, rest } = extractSseEvents(
      'data:{"type":"delta","content":"a"}\n\ndata:{"type":"done"}\n\ndata:{"ty',
    )
    expect(events).toEqual(['{"type":"delta","content":"a"}', '{"type":"done"}'])
    expect(rest).toBe('data:{"ty')
  })

  it('兼容 CRLF 与事件名行', () => {
    const { events, rest } = extractSseEvents('event:message\r\ndata:{"type":"delta"}\r\n\r\n')
    expect(events).toEqual(['{"type":"delta"}'])
    expect(rest).toBe('')
  })

  it('忽略注释与空数据', () => {
    const { events } = extractSseEvents(': ping\n\ndata:\n\n')
    expect(events).toEqual([])
  })
})
