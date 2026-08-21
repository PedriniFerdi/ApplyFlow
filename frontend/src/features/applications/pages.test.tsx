import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { AuthProvider } from '@/features/auth/AuthProvider'
import { clearCsrfToken } from '@/lib/api-client'

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
    const trackerItems = [
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
      if (url.includes('/applications?')) return json({ items: trackerItems, page: 0, size: 100, totalElements: trackerItems.length, totalPages: 1 })
      return json([])
    })

    renderRoute('/tracker')

    expect(await screen.findByRole('heading', { name: 'Wishlist' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Applied' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Interview' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Offer' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Closed' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Archive Labs Platform Engineer/ })).toBeInTheDocument()
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
