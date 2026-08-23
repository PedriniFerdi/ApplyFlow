import { afterEach, describe, expect, it, vi } from 'vitest'
import { analyticsKeys, getAnalyticsFunnel, getAnalyticsSummary, getApplicationsOverTime, getResponseTimeAnalytics, getSourceAnalytics, getTechnologyAnalytics } from './api'

function json(body: unknown) { return new Response(JSON.stringify(body), { status: 200 }) }

describe('analytics API', () => {
  afterEach(() => vi.restoreAllMocks())

  it('defines stable resource keys with the uppercase period in time-series identity', () => {
    expect(analyticsKeys.summary).toEqual(['analytics', 'summary'])
    expect(analyticsKeys.applicationsOverTime('WEEK')).toEqual(['analytics', 'applications-over-time', 'WEEK'])
    expect(analyticsKeys.applicationsOverTime('MONTH')).toEqual(['analytics', 'applications-over-time', 'MONTH'])
  })

  it('requests all six endpoint contracts with AbortSignal and preserves nullable response times', async () => {
    const controller = new AbortController()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/response-time')
      ? json({ sampleSize: 0, averageDays: null, medianDays: null })
      : json({}))
    const results = await Promise.all([
      getAnalyticsSummary(controller.signal), getAnalyticsFunnel(controller.signal), getApplicationsOverTime('WEEK', controller.signal),
      getSourceAnalytics(controller.signal), getTechnologyAnalytics(controller.signal), getResponseTimeAnalytics(controller.signal),
    ])
    expect(fetchMock.mock.calls.map(([input]) => input.toString().replace(/^.*\/api/, ''))).toEqual([
      '/analytics/summary', '/analytics/funnel', '/analytics/applications-over-time?period=WEEK',
      '/analytics/sources', '/analytics/technologies', '/analytics/response-time',
    ])
    expect(fetchMock.mock.calls.every(([, init]) => init?.signal === controller.signal)).toBe(true)
    expect(results[5]).toEqual({ sampleSize: 0, averageDays: null, medianDays: null })
  })
})
