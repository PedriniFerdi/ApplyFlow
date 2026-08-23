import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { clearCsrfToken } from '@/lib/api-client'

const currentUser = { id: 1, fullName: 'Test User', email: 'test@example.com', emailVerified: true, authenticationMethods: ['PASSWORD'] }
const summary = (totalApplications: number) => ({ totalApplications, appliedApplications: totalApplications, responseCount: 0, responseRate: 0, interviewCount: 0, interviewRate: 0, offerCount: 0, offerRate: 0, rejectionCount: 0, rejectionRate: 0 })
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }) }
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

  it('announces loading without rendering fabricated metrics', async () => {
    let resolveSummary!: (response: Response) => void
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : new Promise((resolve) => { resolveSummary = resolve }))
    renderRoute()
    expect(await screen.findByText('Loading analytics')).toBeInTheDocument()
    expect(screen.queryByText(/0%|0 days/i)).not.toBeInTheDocument()
    resolveSummary(json(summary(0)))
    expect(await screen.findByRole('heading', { name: 'No analytics yet' })).toBeInTheDocument()
  })

  it('renders an actionable empty state without zero-value insights', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : json(summary(0)))
    renderRoute()
    expect(await screen.findByRole('heading', { name: 'No analytics yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add application' })).toHaveAttribute('href', '/applications/new')
    expect(screen.queryByText(/0%|0 days/i)).not.toBeInTheDocument()
  })

  it('recovers from a retryable summary error', async () => {
    const user = userEvent.setup()
    let summaryCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) return json(currentUser)
      return ++summaryCalls === 1 ? json({ detail: 'Analytics unavailable' }, 500) : json(summary(3))
    })
    renderRoute()
    expect(await screen.findByRole('alert')).toHaveTextContent('Analytics unavailable')
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('heading', { name: 'Analytics data is ready' })).toBeInTheDocument()
  })

  it('shows server-backed readiness and navigation without requesting unused analytics', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/me') ? json(currentUser) : json(summary(3)))
    renderRoute()
    expect(await screen.findByText('Insights are based on 3 applications.')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Analytics' })).toHaveLength(2)
    expect(fetchMock.mock.calls.filter(([input]) => input.toString().includes('/analytics/'))).toHaveLength(1)
    expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/analytics/summary'))).toBe(true)
  })
})
