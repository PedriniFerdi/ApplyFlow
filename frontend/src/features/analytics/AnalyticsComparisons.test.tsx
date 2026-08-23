import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AnalyticsComparisons } from './AnalyticsComparisons'

const linkedIn = { id: 1, name: 'LinkedIn', applicationCount: 5, responseCount: 3, responseRate: 60, interviewCount: 2, interviewRate: 40, offerCount: 1, offerRate: 20, rejectionCount: 1, rejectionRate: 20 }
const referral = { id: 2, name: 'Referral', applicationCount: 2, responseCount: 1, responseRate: 50, interviewCount: 1, interviewRate: 50, offerCount: 0, offerRate: 0, rejectionCount: 1, rejectionRate: 50 }
const java = { id: 3, name: 'Java', applicationCount: 4, responseCount: 4, responseRate: 100, interviewCount: 3, interviewRate: 75, offerCount: 1, offerRate: 25, rejectionCount: 0, rejectionRate: 0 }
const spring = { id: 4, name: 'Spring Boot', applicationCount: 4, responseCount: 2, responseRate: 50, interviewCount: 2, interviewRate: 50, offerCount: 1, offerRate: 25, rejectionCount: 1, rejectionRate: 25 }
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }) }
function renderComparisons() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><AnalyticsComparisons /></QueryClientProvider>)
}

describe('AnalyticsComparisons', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renders every source and technology with complete server-backed metric tables', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/analytics/sources') ? json({ items: [linkedIn, referral] }) : json({ items: [java, spring] }))
    renderComparisons()
    const sources = await screen.findByRole('table', { name: 'Complete sources analytics' })
    const technologies = await screen.findByRole('table', { name: 'Complete technologies analytics' })
    expect(within(sources).getAllByRole('columnheader')).toHaveLength(10)
    expect(within(sources).getAllByRole('row')).toHaveLength(3)
    expect(within(sources).getByRole('row', { name: 'LinkedIn 5 3 60% 2 40% 1 20% 1 20%' })).toBeInTheDocument()
    expect(within(sources).getByRole('row', { name: 'Referral 2 1 50% 1 50% 0 0% 1 50%' })).toBeInTheDocument()
    expect(within(technologies).getAllByRole('columnheader')).toHaveLength(10)
    expect(within(technologies).getAllByRole('row')).toHaveLength(3)
    expect(within(technologies).getByRole('row', { name: 'Java 4 4 100% 3 75% 1 25% 0 0%' })).toBeInTheDocument()
    expect(within(technologies).getByRole('row', { name: 'Spring Boot 4 2 50% 2 50% 1 25% 1 25%' })).toBeInTheDocument()
    expect(screen.getByText(/Technologies can overlap because one application may appear in multiple rows/)).toBeInTheDocument()
  })

  it('renders honest independent empty states', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => json({ items: [] }))
    renderComparisons()
    expect(await screen.findByText('No source analytics yet.')).toBeInTheDocument()
    expect(await screen.findByText('No technology analytics yet.')).toBeInTheDocument()
  })

  it('keeps technologies visible while sources fail and retry locally', async () => {
    let sourceCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/analytics/sources') ? ++sourceCalls === 1 ? json({ detail: 'private source failure' }, 500) : json({ items: [linkedIn] }) : json({ items: [java] }))
    const user = userEvent.setup()
    renderComparisons()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Sources could not be loaded')
    expect(screen.getByRole('table', { name: 'Complete technologies analytics' })).toBeInTheDocument()
    expect(screen.queryByText('private source failure')).not.toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table', { name: 'Complete sources analytics' })).toBeInTheDocument()
  })

  it('keeps sources visible while technologies fail and retry locally', async () => {
    let technologyCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/analytics/technologies') ? ++technologyCalls === 1 ? json({ detail: 'private technology failure' }, 500) : json({ items: [java] }) : json({ items: [linkedIn] }))
    const user = userEvent.setup()
    renderComparisons()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Technologies could not be loaded')
    expect(screen.getByRole('table', { name: 'Complete sources analytics' })).toBeInTheDocument()
    expect(screen.queryByText('private technology failure')).not.toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table', { name: 'Complete technologies analytics' })).toBeInTheDocument()
  })
})
