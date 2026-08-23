import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AnalyticsProgress, formatBucketDate } from './AnalyticsProgress'

const stages = [
  { status: 'APPLIED', count: 4 }, { status: 'RESPONSE_RECEIVED', count: 2 }, { status: 'HR_INTERVIEW', count: 3 },
  { status: 'TECHNICAL_INTERVIEW', count: 1 }, { status: 'FINAL_INTERVIEW', count: 0 }, { status: 'OFFER', count: 1 },
]
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }) }
function renderProgress() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><AnalyticsProgress /></QueryClientProvider>)
}

describe('AnalyticsProgress', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renders sparse non-cumulative buckets and switches only the all-time grouping query', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/analytics/funnel')) return json({ stages })
      return url.endsWith('MONTH') ? json({ period: 'MONTH', buckets: [{ startDate: '2026-08-01', applicationCount: 7 }] }) : json({ period: 'WEEK', buckets: [{ startDate: '2026-08-03', applicationCount: 2 }, { startDate: '2026-08-17', applicationCount: 5 }] })
    })
    const user = userEvent.setup()
    renderProgress()
    const seriesTable = await screen.findByRole('table', { name: 'All-time applications grouped by week' })
    expect(within(seriesTable).getAllByRole('row')).toHaveLength(3)
    expect(screen.getByText(formatBucketDate('2026-08-03'))).toHaveAttribute('datetime', '2026-08-03')
    expect(screen.getByText(formatBucketDate('2026-08-17'))).toHaveAttribute('datetime', '2026-08-17')
    await user.selectOptions(screen.getByLabelText('Group all-time history by'), 'MONTH')
    expect(await screen.findByRole('table', { name: 'All-time applications grouped by month' })).toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([input]) => input.toString().includes('applications-over-time'))).toHaveLength(2)
    expect(fetchMock.mock.calls.filter(([input]) => input.toString().endsWith('/analytics/funnel'))).toHaveLength(1)
  })

  it('renders six exact recorded milestone counts without conversion or assessment semantics', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/analytics/funnel') ? json({ stages }) : json({ period: 'WEEK', buckets: [] }))
    renderProgress()
    const table = await screen.findByRole('table', { name: 'Recorded application milestone counts' })
    expect(within(table).getAllByRole('row')).toHaveLength(7)
    expect(within(table).getByRole('row', { name: 'Applied 4' })).toBeInTheDocument()
    expect(within(table).getByRole('row', { name: 'HR interview 3' })).toBeInTheDocument()
    expect(screen.getByText('Counts are recorded milestones, not conversion rates.')).toBeInTheDocument()
    expect(screen.queryByText(/assessment/i)).not.toBeInTheDocument()
  })

  it('keeps milestones visible while the time series fails and retries independently', async () => {
    let seriesCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/analytics/funnel') ? json({ stages }) : ++seriesCalls === 1 ? json({ detail: 'private series failure' }, 500) : json({ period: 'WEEK', buckets: [{ startDate: '2026-08-03', applicationCount: 2 }] }))
    const user = userEvent.setup()
    renderProgress()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Applications over time could not be loaded')
    expect(screen.getByRole('table', { name: 'Recorded application milestone counts' })).toBeInTheDocument()
    expect(screen.queryByText('private series failure')).not.toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table', { name: 'All-time applications grouped by week' })).toBeInTheDocument()
  })

  it('keeps the time series visible while milestones fail and retries independently', async () => {
    let funnelCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/analytics/funnel') ? ++funnelCalls === 1 ? json({ detail: 'private funnel failure' }, 500) : json({ stages }) : json({ period: 'WEEK', buckets: [{ startDate: '2026-08-03', applicationCount: 2 }] }))
    const user = userEvent.setup()
    renderProgress()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Recorded milestones could not be loaded')
    expect(screen.getByRole('table', { name: 'All-time applications grouped by week' })).toBeInTheDocument()
    expect(screen.queryByText('private funnel failure')).not.toBeInTheDocument()
    await user.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('table', { name: 'Recorded application milestone counts' })).toBeInTheDocument()
  })

  it('announces independent loading and honest empty progress states', async () => {
    let resolveSeries!: (response: Response) => void
    let resolveFunnel!: (response: Response) => void
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => new Promise((resolve) => { if (input.toString().endsWith('/analytics/funnel')) resolveFunnel = resolve; else resolveSeries = resolve }))
    renderProgress()
    expect(await screen.findByText('Loading applications over time')).toBeInTheDocument()
    expect(screen.getByText('Loading recorded milestones')).toBeInTheDocument()
    resolveSeries(json({ period: 'WEEK', buckets: [] }))
    resolveFunnel(json({ stages: stages.map((stage) => ({ ...stage, count: 0 })) }))
    expect(await screen.findByText('No dated applications for this grouping.')).toBeInTheDocument()
    expect(await screen.findByText('No recorded milestones yet.')).toBeInTheDocument()
  })
})
