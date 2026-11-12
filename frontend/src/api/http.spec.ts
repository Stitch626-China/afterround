import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, get } from './http'

function mockFetch(handler: () => Promise<Response>): void {
  vi.stubGlobal('fetch', vi.fn(handler))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('http request', () => {
  it('code = 0 时解包 data', async () => {
    mockFetch(async () => new Response(JSON.stringify({ code: 0, message: 'ok', data: { id: 1 } }), { status: 200 }))
    await expect(get<{ id: number }>('/x')).resolves.toEqual({ id: 1 })
  })

  it('业务错误抛 ApiError 并带错误码', async () => {
    mockFetch(async () => new Response(
      JSON.stringify({ code: 40401, message: '简历不存在', traceId: 't-1' }),
      { status: 404 },
    ))
    await expect(get('/x')).rejects.toMatchObject({ name: 'ApiError', code: 40401, message: '简历不存在' })
  })

  it('非 JSON 响应给友好提示', async () => {
    mockFetch(async () => new Response('<html>bad gateway</html>', { status: 502 }))
    await expect(get('/x')).rejects.toEqual(expect.objectContaining({
      name: 'ApiError',
      message: '请求失败（HTTP 502）',
    }))
  })

  it('网络失败给连接提示', async () => {
    mockFetch(async () => {
      throw new TypeError('failed to fetch')
    })
    await expect(get('/x')).rejects.toBeInstanceOf(ApiError)
    await expect(get('/x').catch((ex: ApiError) => ex.message))
      .resolves.toContain('无法连接后端服务')
  })
})
