import { afterEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { createElement, type ReactNode } from 'react'
import { analyticsKeys } from '@/features/analytics/api'
import { applicationKeys, applicationSearchParams, getTrackerPage, invalidateApplicationData, useTracker } from './api'

describe('applicationSearchParams', () => {
  afterEach(() => vi.restoreAllMocks())
  it('serializes repeated status filters and omits empty optional values', () => {
    const result = applicationSearchParams({ status: ['APPLIED', 'OFFER'], sourceId: 2, page: 3, size: 20, sortBy: 'positionTitle', direction: 'ASC' })
    expect(result.getAll('status')).toEqual(['APPLIED', 'OFFER'])
    expect(result.get('sourceId')).toBe('2')
    expect(result.get('technologyId')).toBeNull()
    expect(result.get('page')).toBe('3')
    expect(result.get('sortBy')).toBe('positionTitle')
  })

  it('requests an exact tracker stage page with its filters and abort signal', async () => {
    const controller = new AbortController()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ items: [], page: 3, size: 8, totalElements: 0, totalPages: 0 }), { status: 200 }))

    await getTrackerPage(['APPLIED', 'RESPONSE_RECEIVED'], 3, controller.signal)

    const [input, init] = fetchMock.mock.calls[0]
    const search = new URLSearchParams(input.toString().split('?')[1])
    expect(search.getAll('status')).toEqual(['APPLIED', 'RESPONSE_RECEIVED'])
    expect(search.get('page')).toBe('3')
    expect(search.get('size')).toBe('8')
    expect(search.get('sortBy')).toBe('updatedAt')
    expect(init?.signal).toBe(controller.signal)
  })

  it('requests the next page only for the selected tracker stage', async () => {
    const first = { items: [], page: 0, size: 8, totalElements: 9, totalPages: 2 }
    const second = { items: [], page: 1, size: 8, totalElements: 9, totalPages: 2 }
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify(first), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(second), { status: 200 }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children)
    const { result } = renderHook(() => useTracker('offer', ['OFFER']), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    await act(() => result.current.fetchNextPage())

    expect(fetchMock.mock.calls).toHaveLength(2)
    expect(fetchMock.mock.calls[1][0].toString()).toContain('status=OFFER')
    expect(fetchMock.mock.calls[1][0].toString()).toContain('page=1')
  })

  it('invalidates analytics after application writes and deletion', async () => {
    const client = new QueryClient()
    client.setQueryData(analyticsKeys.summary, { totalApplications: 1 })
    client.setQueryData(applicationKeys.trackerStage('offer'), { pages: [], pageParams: [] })
    await invalidateApplicationData(client)
    expect(client.getQueryState(analyticsKeys.summary)?.isInvalidated).toBe(true)
    expect(client.getQueryState(applicationKeys.trackerStage('offer'))?.isInvalidated).toBe(true)
  })
})
