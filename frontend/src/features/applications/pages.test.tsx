import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { clearCsrfToken } from '@/lib/api-client'
import type { JobApplicationSummary } from '@/types/api'

const emptyPage = { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }
const detail = {
  id: 42,
  company: { id: 7, name: 'Acme', website: 'https://example.com', companyType: 'CORPORATE', industry: 'Software' },
  positionTitle: 'Platform Engineer', jobUrl: 'https://example.com/jobs/42', appliedDate: '2026-08-01', status: 'APPLIED',
  source: { id: 1, name: 'LinkedIn' }, workMode: 'REMOTE', location: 'Buenos Aires', salaryMin: null, salaryMax: null,
  currency: null, salaryPeriod: null, notes: 'Follow up next week', technologies: [{ id: 3, name: 'Java' }],
  createdAt: '2026-08-01T12:00:00Z', updatedAt: '2026-08-02T12:00:00Z', history: [{ id: 1, status: 'APPLIED', changedAt: '2026-08-01T12:00:00Z' }],
}
const currentUser = { id: 1, fullName: 'Test User', email: 'test@example.com', emailVerified: true, authenticationMethods: ['PASSWORD'] }

function summary(id: number, status: JobApplicationSummary['status'], prefix = 'Stage'): JobApplicationSummary {
  return {
    id, company: { id, name: `${prefix} company ${id}`, website: null, companyType: 'STARTUP', industry: null }, positionTitle: `${prefix} role ${id}`,
    status, appliedDate: '2026-08-01', source: { id: 1, name: 'LinkedIn' }, workMode: 'REMOTE', technologies: [],
    createdAt: '2026-08-01T12:00:00Z', updatedAt: '2026-08-01T12:00:00Z',
  }
}

function stagePage(url: string, items: JobApplicationSummary[]) {
  const search = new URLSearchParams(url.split('?')[1])
  const statuses = search.getAll('status')
  const page = Number(search.get('page'))
  const size = Number(search.get('size'))
  const matching = items.filter(({ status }) => statuses.includes(status))
  return { items: matching.slice(page * size, (page + 1) * size), page, size, totalElements: matching.length, totalPages: Math.ceil(matching.length / size) }
}

function renderRoute(route: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><AuthProvider><App /></AuthProvider></MemoryRouter></QueryClientProvider>)
}

function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }) }

describe('application pages', () => {
  afterEach(() => {
    clearCsrfToken()
    vi.restoreAllMocks()
  })

  it('keeps authentication stable while intent prefetch warms the lazy new-application route', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.includes('/applications?')) return json(emptyPage)
      return json([])
    })

    renderRoute('/applications')
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()

    fireEvent.mouseEnter(screen.getAllByRole('link', { name: 'New application' })[0])

    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/auth/csrf'))).toBe(true))
    expect(fetchMock.mock.calls.filter(([input]) => input.toString().endsWith('/auth/me'))).toHaveLength(1)
  })

  it('exposes Security settings from compact navigation with a visible touch focus target', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) return json(currentUser)
      return input.toString().includes('/applications?') ? json(emptyPage) : json([])
    })
    renderRoute('/applications')

    const security = await screen.findByRole('link', { name: 'Security settings' })
    expect(security).toHaveAttribute('href', '/settings/security')
    expect(security).toHaveClass('h-11', 'w-11', 'focus-visible:ring-2')
    expect(security.parentElement).toHaveClass('lg:hidden')
  })

  it('renders the actionable empty state after catalogs and applications load', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      if (url.includes('/applications?')) return json(emptyPage)
      return json([])
    })
    renderRoute('/applications')
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add application' })).toHaveAttribute('href', '/applications/new')
  })

  it('renders a retryable list error without showing stale data', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) return json(currentUser)
      return input.toString().includes('/applications?') ? json({ detail: 'Database unavailable' }, 500) : json([])
    })
    renderRoute('/applications')
    expect(await screen.findByText('Database unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
  })

  it('renders the four active tracker columns and keeps closed applications visible', async () => {
    const trackerItems: JobApplicationSummary[] = [
      {
        id: 11,
        company: { id: 2, name: 'Northstar', website: null, companyType: 'STARTUP', industry: null },
        positionTitle: 'Product Designer',
        status: 'BOOKMARKED',
        appliedDate: null,
        source: { id: 1, name: 'LinkedIn' },
        workMode: 'REMOTE',
        technologies: [],
        createdAt: '2026-08-01T12:00:00Z',
        updatedAt: '2026-08-01T12:00:00Z',
      },
      {
        id: 12,
        company: { id: 3, name: 'Archive Labs', website: null, companyType: 'CORPORATE', industry: null },
        positionTitle: 'Platform Engineer',
        status: 'REJECTED',
        appliedDate: '2026-08-02',
        source: { id: 1, name: 'LinkedIn' },
        workMode: 'HYBRID',
        technologies: [],
        createdAt: '2026-08-02T12:00:00Z',
        updatedAt: '2026-08-02T12:00:00Z',
      },
    ]
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      if (url.includes('/applications?')) return json(stagePage(url, trackerItems))
      return json([])
    })

    renderRoute('/tracker')

    expect(await screen.findByRole('heading', { name: 'Wishlist' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Applied' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Interview' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Offer' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Closed' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Archive Labs Platform Engineer/ })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Active application stages' })).toHaveAttribute('tabindex', '0')
    expect(screen.getByText('Swipe or scroll horizontally to explore application stages.')).toBeInTheDocument()
  })

  it('bounds a 500-item tracker to 40 initial cards and loads eight more only for the requested stage', async () => {
    const stageFixtures = [
      ['BOOKMARKED', 'Wishlist'], ['APPLIED', 'Applied'], ['HR_INTERVIEW', 'Interview'], ['OFFER', 'Offer'], ['REJECTED', 'Closed'],
    ] as const
    const trackerItems = stageFixtures.flatMap(([status, prefix], stage) => Array.from({ length: 100 }, (_, index) => summary(stage * 100 + index + 1, status, prefix)))
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      return url.includes('/applications?') ? json(stagePage(url, trackerItems)) : json([])
    })
    const user = userEvent.setup()
    renderRoute('/tracker')

    expect(await screen.findByText('Offer company 308')).toBeInTheDocument()
    expect(screen.getAllByText(/company \d+$/)).toHaveLength(40)
    expect(screen.queryByText('Offer company 309')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Offer' }).parentElement).toHaveTextContent('100')
    await user.click(screen.getByRole('button', { name: 'Load more offer' }))
    expect(await screen.findByText('Offer company 316')).toBeInTheDocument()
    expect(screen.getAllByText(/company \d+$/)).toHaveLength(48)

    const applicationCalls = fetchMock.mock.calls.map(([input]) => input.toString()).filter((url) => url.includes('/applications?'))
    expect(applicationCalls.filter((url) => url.includes('status=OFFER'))).toHaveLength(2)
    expect(applicationCalls.filter((url) => !url.includes('status=OFFER'))).toHaveLength(4)
  })

  it('keeps successful tracker stages visible while a failed stage retries locally', async () => {
    const trackerItems = [summary(1, 'OFFER', 'Stable'), summary(2, 'HR_INTERVIEW', 'Recovered')]
    let interviewAttempts = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      if (url.includes('status=HR_INTERVIEW') && interviewAttempts++ === 0) return json({ detail: 'Interview stage unavailable' }, 500)
      return url.includes('/applications?') ? json(stagePage(url, trackerItems)) : json([])
    })
    const user = userEvent.setup()
    renderRoute('/tracker')

    expect(await screen.findByText('Stable company 1')).toBeInTheDocument()
    const interview = screen.getByRole('heading', { name: 'Interview' }).closest('section')!
    expect(within(interview).getByRole('alert')).toHaveTextContent('Interview stage unavailable')
    await user.click(within(interview).getByRole('button', { name: 'Try again' }))
    expect(await within(interview).findByText('Recovered company 2')).toBeInTheDocument()
    expect(screen.getByText('Stable company 1')).toBeInTheDocument()
  })

  it('shows detail and only navigates away after delete returns no content', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(currentUser)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/applications/42') && init?.method === 'DELETE') return new Response(null, { status: 204 })
      if (url.endsWith('/applications/42')) return json(detail)
      if (url.includes('/applications?')) return json(emptyPage)
      return json([])
    })
    renderRoute('/applications/42')
    expect(await screen.findByRole('heading', { name: 'Platform Engineer' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Delete permanently' }))
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url, init]) => url.toString().endsWith('/applications/42') && init?.method === 'DELETE')).toBe(true)
  })
})
