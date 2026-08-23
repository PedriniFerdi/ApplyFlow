import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { clearCsrfToken } from '@/lib/api-client'

const currentUser = { id: 1, fullName: 'Test User', email: 'test@example.com', emailVerified: true, authenticationMethods: ['PASSWORD'] }
const summary = (overrides = {}) => ({ totalApplications: 8, appliedApplications: 6, responseCount: 3, responseRate: 50, interviewCount: 2, interviewRate: 33.33, offerCount: 1, offerRate: 16.67, rejectionCount: 2, rejectionRate: 33.33, ...overrides })
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }) }
function detail(
  input: RequestInfo | URL,
  responseTime: { sampleSize: number; averageDays: number | null; medianDays: number | null } = { sampleSize: 0, averageDays: null, medianDays: null },
) {
  const url = input.toString()
  if (url.endsWith('/analytics/funnel')) return json({ stages: [] })
  if (url.includes('/analytics/applications-over-time')) return json({ period: url.endsWith('MONTH') ? 'MONTH' : 'WEEK', buckets: [] })
  if (url.endsWith('/analytics/sources') || url.endsWith('/analytics/technologies')) return json({ items: [] })
  return json(responseTime)
}
function renderRoute() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/analytics']}><AuthProvider><App /></AuthProvider></MemoryRouter></QueryClientProvider>)
}

describe('AnalyticsPage', () => {
  afterEach(() => { clearCsrfToken(); vi.restoreAllMocks() })

  it('keeps analytics protected without issuing eager analytics requests', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute()
    expect(await screen.findByRole('heading', { name: 'Sign in to ApplyFlow' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => input.toString().includes('/analytics/'))).toBe(false)
  })

  it('preserves global loading and empty states without requesting response time', async () => {
    let resolveSummary!: (response: Response) => void
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : new Promise((resolve) => { resolveSummary = resolve }))
    renderRoute()
    expect(await screen.findByText('Loading analytics')).toBeInTheDocument()
    expect(screen.queryByText(/0%|0 days/i)).not.toBeInTheDocument()
    resolveSummary(json(summary({ totalApplications: 0, appliedApplications: 0 })))
    expect(await screen.findByRole('heading', { name: 'No analytics yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add application' })).toHaveAttribute('href', '/applications/new')
    expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/analytics/response-time'))).toBe(false)
  })

  it('recovers from a retryable summary error before loading the overview', async () => {
    const user = userEvent.setup()
    let summaryCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      if (url.endsWith('/analytics/summary')) return ++summaryCalls === 1 ? json({ detail: 'Analytics unavailable' }, 500) : json(summary())
      return detail(input)
    })
    renderRoute()
    expect(await screen.findByRole('alert')).toHaveTextContent('Analytics unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Applications sent')).toBeInTheDocument()
  })

  it('renders five server-backed summary metrics with explicit sent-application denominators', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : input.toString().endsWith('/analytics/summary') ? json(summary()) : detail(input, { sampleSize: 4, averageDays: 3.5, medianDays: 2 }))
    renderRoute()
    expect(await screen.findByText('Applications sent')).toBeInTheDocument()
    expect(screen.getByText('Interviews')).toBeInTheDocument()
    expect(screen.getByText('Offers')).toBeInTheDocument()
    expect(screen.getByText('Response rate')).toBeInTheDocument()
    expect(screen.getByText('Rejections')).toBeInTheDocument()
    expect(screen.getAllByText(/33[.,]33% of 6 sent applications/)).toHaveLength(2)
    expect(screen.getByText('3 responses from 6 sent applications')).toBeInTheDocument()
    expect(await screen.findByText(/3[.,]5 days/)).toBeInTheDocument()
    expect(screen.getByText('2 days')).toBeInTheDocument()
    expect(screen.getByText('4')).toBeInTheDocument()
  })

  it('uses an em dash for bookmark-only response rate and never fabricates response days', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : input.toString().endsWith('/analytics/summary') ? json(summary({ totalApplications: 2, appliedApplications: 0, responseCount: 0, responseRate: 0, interviewCount: 0, interviewRate: 0, offerCount: 0, offerRate: 0, rejectionCount: 0, rejectionRate: 0 })) : detail(input))
    renderRoute()
    expect(await screen.findByText('—')).toBeInTheDocument()
    expect(screen.getAllByText('Rate unavailable — no sent applications')).toHaveLength(4)
    expect(await screen.findByText(/No response-time sample yet/)).toBeInTheDocument()
    expect(screen.queryByText(/0 days/i)).not.toBeInTheDocument()
  })

  it('does not fabricate zero days when a non-empty response sample has nullable metrics', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : input.toString().endsWith('/analytics/summary') ? json(summary()) : detail(input, { sampleSize: 3, averageDays: null, medianDays: 2 }))
    renderRoute()
    expect(await screen.findByText('Complete response-time metrics are unavailable for this sample of 3 measured responses.')).toBeInTheDocument()
    expect(screen.queryByText(/0 days/i)).not.toBeInTheDocument()
  })

  it('keeps summary metrics visible while response time retries independently', async () => {
    const user = userEvent.setup()
    let responseCalls = 0
    let resolveRetry!: (response: Response) => void
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      if (url.endsWith('/analytics/summary')) return json(summary())
      if (!url.endsWith('/analytics/response-time')) return detail(input)
      if (++responseCalls === 1) return json({ detail: 'internal diagnostics' }, 500)
      return new Promise((resolve) => { resolveRetry = resolve })
    })
    renderRoute()
    expect(await screen.findByRole('alert')).toHaveTextContent('Response time could not be loaded')
    expect(screen.getByText('Applications sent')).toBeInTheDocument()
    expect(screen.queryByText('internal diagnostics')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(screen.getByText('Loading response time')).toBeInTheDocument()
    resolveRetry(json({ sampleSize: 2, averageDays: 1, medianDays: 1 }))
    expect(await screen.findAllByText('1 day')).toHaveLength(2)
  })

  it('requests only the overview and progress resources in this slice', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : input.toString().endsWith('/analytics/summary') ? json(summary()) : detail(input))
    renderRoute()
    expect(await screen.findByText(/No response-time sample yet/)).toBeInTheDocument()
    const analyticsUrls = fetchMock.mock.calls.map(([input]) => input.toString()).filter((url) => url.includes('/analytics/'))
    expect(analyticsUrls).toHaveLength(6)
    expect(analyticsUrls.some((url) => url.endsWith('/analytics/summary'))).toBe(true)
    expect(analyticsUrls.some((url) => url.endsWith('/analytics/response-time'))).toBe(true)
    expect(analyticsUrls.some((url) => url.endsWith('/analytics/funnel'))).toBe(true)
    expect(analyticsUrls.some((url) => url.endsWith('/analytics/applications-over-time?period=WEEK'))).toBe(true)
    expect(analyticsUrls.some((url) => url.endsWith('/analytics/sources'))).toBe(true)
    expect(analyticsUrls.some((url) => url.endsWith('/analytics/technologies'))).toBe(true)
  })
})
