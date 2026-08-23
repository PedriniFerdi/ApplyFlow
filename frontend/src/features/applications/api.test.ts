import { afterEach, describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { analyticsKeys } from '@/features/analytics/api'
import { applicationSearchParams, getAllApplications, invalidateApplicationData } from './api'

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

  it('loads every backend page for the tracker without truncating at 100 items', async () => {
    const first = { items: [{ id: 1 }], page: 0, size: 100, totalElements: 2, totalPages: 2 }
    const second = { items: [{ id: 2 }], page: 1, size: 100, totalElements: 2, totalPages: 2 }
    const fetchMock = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response(JSON.stringify(first), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(second), { status: 200 }))

    const result = await getAllApplications()
    expect(result.map(({ id }) => id)).toEqual([1, 2])
    expect(fetchMock.mock.calls[0][0].toString()).toContain('page=0')
    expect(fetchMock.mock.calls[1][0].toString()).toContain('page=1')
  })

  it('invalidates analytics after application writes and deletion', async () => {
    const client = new QueryClient()
    client.setQueryData(analyticsKeys.summary, { totalApplications: 1 })
    await invalidateApplicationData(client)
    expect(client.getQueryState(analyticsKeys.summary)?.isInvalidated).toBe(true)
  })
})
