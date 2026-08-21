import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiProblem, apiRequest, clearCsrfToken, prefetchCsrfToken } from './api-client'

describe('apiRequest', () => {
  afterEach(() => { clearCsrfToken(); vi.restoreAllMocks() })

  it('returns undefined for a successful no-content response', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (input.toString().endsWith('/auth/csrf')) {
        return new Response(JSON.stringify({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }), { status: 200 })
      }
      expect(init?.credentials).toBe('include')
      expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe('test-token')
      return new Response(null, { status: 204 })
    })
    await expect(apiRequest<void>('/applications/1', { method: 'DELETE' })).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('deduplicates concurrent speculative CSRF prefetches', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ token: 'test-token', headerName: 'X-CSRF-TOKEN' }), { status: 200 }),
    )

    await Promise.all([prefetchCsrfToken(), prefetchCsrfToken()])

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('preserves RFC 7807 detail and field errors', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({
      status: 400,
      title: 'Validation failed',
      detail: 'One or more fields are invalid',
      errors: { positionTitle: 'must not be blank' },
    }), { status: 400, headers: { 'Content-Type': 'application/problem+json' } }))

    const error = await apiRequest('/applications').catch((reason: unknown) => reason)
    expect(error).toBeInstanceOf(ApiProblem)
    expect(error).toMatchObject({ status: 400, title: 'Validation failed', errors: { positionTitle: 'must not be blank' } })
  })

  it('uses a safe fallback when an error body is not JSON', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('gateway failed', { status: 502 }))
    await expect(apiRequest('/applications')).rejects.toMatchObject({ status: 502, message: 'The server returned status 502.' })
  })
})
