import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '@/lib/api-client'
import { ApplicationForm } from './ApplicationForm'

const extraction = {
  canonicalUrl: 'https://jobs.linkedin.com/view/42',
  positionTitle: 'Backend Engineer',
  company: { name: 'Acme', website: 'https://acme.example', existingCompanyId: null },
  sourceId: 1,
  workMode: 'REMOTE' as const,
  location: 'Buenos Aires',
  salary: { min: '90000', max: '120000', currency: 'USD', period: 'YEARLY' as const },
  warnings: [],
  confidence: { positionTitle: 'HIGH' as const },
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderForm(onSubmit = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return { onSubmit, ...render(<QueryClientProvider client={client}><ApplicationForm enableJobUrlImport submitLabel="Save application" pending={false} onSubmit={onSubmit} /></QueryClientProvider>) }
}

function catalogResponse(url: string) {
  if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
  if (url.endsWith('/sources')) return json([{ id: 1, name: 'LinkedIn' }])
  if (url.includes('/companies')) return json([{ id: 7, name: 'Acme', website: 'https://acme.example', companyType: 'CORPORATE', industry: null }])
  return json([])
}

describe('new application URL import', () => {
  afterEach(() => { clearCsrfToken(); vi.restoreAllMocks() })

  it('renders the compact import-first hierarchy with accessible progressive disclosure', () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => catalogResponse(input.toString()))
    renderForm()

    expect(screen.getByRole('textbox', { name: 'Paste job posting URL' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Import job' })).toBeEnabled()
    expect(screen.getByText('or enter manually')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Review application' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save application' })).toBeEnabled()
    expect(screen.getByRole('combobox', { name: 'Company name' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Select company')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Search companies')).not.toBeInTheDocument()

    const summary = screen.getByText(/Additional details/).closest('summary')
    expect(summary?.parentElement).not.toHaveAttribute('open')
    fireEvent.click(summary!)
    expect(summary?.parentElement).toHaveAttribute('open')
    expect(screen.getByRole('textbox', { name: 'Notes' })).toBeInTheDocument()
  })

  it('uses the single Company name control to select an existing company with the keyboard', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => catalogResponse(input.toString()))
    const { onSubmit } = renderForm()

    const companyName = screen.getByRole('combobox', { name: 'Company name' })
    await user.type(companyName, 'Acme')
    await screen.findByRole('option', { name: /Acme/ })
    await user.keyboard('{ArrowDown}{Enter}')
    await user.type(screen.getByRole('textbox', { name: 'Position title' }), 'Backend Engineer')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Source' }), '1')
    await user.click(screen.getByRole('button', { name: 'Save application' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ companyId: 7 })))
    expect(onSubmit.mock.calls[0][0].newCompany).toBeUndefined()
    expect(screen.getByText('Using an existing company.')).toBeInTheDocument()
  })

  it('uses a free Company name as a new-company payload without a second selector', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => catalogResponse(input.toString()))
    const { onSubmit } = renderForm()

    await user.type(screen.getByRole('combobox', { name: 'Company name' }), 'NewCo')
    await user.type(screen.getByRole('textbox', { name: 'Position title' }), 'Frontend Engineer')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Source' }), '1')
    await user.click(screen.getByRole('button', { name: 'Save application' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ newCompany: expect.objectContaining({ name: 'NewCo' }) })))
    expect(onSubmit.mock.calls[0][0].companyId).toBeUndefined()
    expect(screen.getByText('No match selected; this company will be created when you save.')).toBeInTheDocument()
  })

  it('reopens additional details and surfaces its validation errors', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => catalogResponse(input.toString()))
    renderForm()

    const summary = screen.getByText(/Additional details/).closest('summary')!
    await user.click(summary)
    await user.type(screen.getByRole('textbox', { name: 'Minimum' }), '100')
    await user.click(summary)
    expect(summary.parentElement).not.toHaveAttribute('open')

    await user.click(screen.getByRole('button', { name: 'Save application' }))

    await waitFor(() => expect(summary.parentElement).toHaveAttribute('open'))
    expect(screen.getByText('2 fields need attention')).toBeInTheDocument()
  })

  it('starts exactly one non-blocking extraction on paste and fills empty fields', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) return json(extraction)
      return catalogResponse(url)
    })
    renderForm()

    const jobUrl = screen.getByRole('textbox', { name: 'Paste job posting URL' })
    fireEvent.paste(jobUrl, { clipboardData: { getData: () => 'https://jobs.linkedin.com/view/42' } })
    fireEvent.click(screen.getByRole('button', { name: 'Importing...' }))

    expect(screen.getByText('Reading the job posting. You can keep editing.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save application' })).toBeEnabled()
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Position title' })).toHaveValue('Backend Engineer'))
    expect(screen.getByRole('combobox', { name: 'Company name' })).toHaveValue('Acme')
    expect(screen.getByRole('textbox', { name: 'Location' })).toHaveValue('Buenos Aires')
    expect(screen.getByText('Fields auto-filled from job posting')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Import job' }))
    expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/job-offers/extract'))).toHaveLength(1)
    expect(fetchMock.mock.calls.find(([url]) => url.toString().endsWith('/job-offers/extract'))?.[1]?.method).toBe('POST')
  })

  it('never overwrites a dirty or non-empty field', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) return json(extraction)
      return catalogResponse(url)
    })
    renderForm()

    const title = screen.getByRole('textbox', { name: 'Position title' })
    await user.type(title, 'My custom title')
    fireEvent.paste(screen.getByRole('textbox', { name: 'Paste job posting URL' }), { clipboardData: { getData: () => 'https://jobs.linkedin.com/view/42' } })

    await screen.findByText('Fields auto-filled from job posting')
    expect(title).toHaveValue('My custom title')
  })

  it('aborts and suppresses a stale extraction response while preserving manual fallback', async () => {
    const responses: Array<(response: Response) => void> = []
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) return new Promise<Response>((resolve) => responses.push(resolve))
      return Promise.resolve(catalogResponse(url))
    })
    renderForm()

    const jobUrl = screen.getByRole('textbox', { name: 'Paste job posting URL' })
    fireEvent.paste(jobUrl, { clipboardData: { getData: () => 'https://example.com/first' } })
    await waitFor(() => expect(responses).toHaveLength(1))
    fireEvent.paste(jobUrl, { clipboardData: { getData: () => 'https://example.com/second' } })
    await waitFor(() => expect(responses).toHaveLength(2))
    responses[1](json({ ...extraction, positionTitle: 'Second role' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Position title' })).toHaveValue('Second role'))
    responses[0](json({ ...extraction, positionTitle: 'Stale role' }))

    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Position title' })).toHaveValue('Second role'))
    expect(jobUrl).toHaveValue('https://example.com/second')
    expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/job-offers/extract'))).toHaveLength(2)
  })

  it('keeps the pasted URL and manual form available when extraction fails', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) return json({ status: 422, detail: 'The job page could not be read.' }, 422)
      return catalogResponse(url)
    })
    renderForm()

    const jobUrl = screen.getByRole('textbox', { name: 'Paste job posting URL' })
    fireEvent.paste(jobUrl, { clipboardData: { getData: () => 'https://example.com/job' } })

    expect(await screen.findByText('The job page could not be read.')).toBeInTheDocument()
    expect(jobUrl).toHaveValue('https://example.com/job')
    expect(screen.getByRole('textbox', { name: 'Position title' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Save application' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled()
  })

  it('invalidates an active extraction when the URL is edited manually', async () => {
    const user = userEvent.setup()
    let resolveExtraction!: (response: Response) => void
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) return new Promise<Response>((resolve) => { resolveExtraction = resolve })
      return Promise.resolve(catalogResponse(url))
    })
    renderForm()

    const jobUrl = screen.getByRole('textbox', { name: 'Paste job posting URL' })
    fireEvent.paste(jobUrl, { clipboardData: { getData: () => 'https://example.com/original' } })
    await screen.findByText('Reading the job posting. You can keep editing.')
    await waitFor(() => expect(resolveExtraction).toBeTypeOf('function'))
    await user.clear(jobUrl)
    await user.type(jobUrl, 'https://example.com/manual')
    resolveExtraction(json({ ...extraction, positionTitle: 'Stale role' }))

    await waitFor(() => expect(jobUrl).toHaveValue('https://example.com/manual'))
    expect(screen.getByRole('textbox', { name: 'Position title' })).toHaveValue('')
  })

  it('supports explicit import, inline retry, and update from the current URL', async () => {
    const user = userEvent.setup()
    let attempts = 0
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) {
        attempts += 1
        return attempts === 1 ? json({ status: 422, detail: 'This job board is not supported yet.' }, 422) : json(extraction)
      }
      return catalogResponse(url)
    })
    renderForm()

    await user.type(screen.getByRole('textbox', { name: 'Paste job posting URL' }), 'https://example.com/job')
    await user.click(screen.getByRole('button', { name: 'Import job' }))
    expect(await screen.findByText('This job board is not supported yet.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Fields auto-filled from job posting')
    expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/job-offers/extract'))).toHaveLength(2)

    await user.click(screen.getByRole('button', { name: 'Update from URL' }))
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/job-offers/extract'))).toHaveLength(3))
  })

  it('treats complete LinkedIn identity metadata as a full success despite provenance warnings', async () => {
    const provenanceWarning = 'This page did not expose structured JobPosting data; only conservative page metadata was used.'
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) return json({
        ...extraction,
        canonicalUrl: 'https://www.linkedin.com/jobs/view/4453575573/',
        positionTitle: 'Senior C# Developer',
        company: { name: 'ROGII Latin America', website: null, existingCompanyId: null },
        location: 'Greater Buenos Aires',
        salary: null,
        warnings: [provenanceWarning],
      })
      return catalogResponse(url)
    })
    renderForm()

    fireEvent.paste(screen.getByRole('textbox', { name: 'Paste job posting URL' }), { clipboardData: { getData: () => 'https://www.linkedin.com/jobs/view/4453575573/' } })

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('Fields auto-filled from job posting')
    expect(status).toHaveTextContent('Review the imported details before saving.')
    expect(status).not.toHaveTextContent(provenanceWarning)
    expect(status.parentElement?.parentElement).toHaveClass('bg-[#edf5ed]')
    expect(screen.queryByText('Some job details were imported')).not.toBeInTheDocument()
  })

  it('shows partial imports when a core application identity field is missing', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/job-offers/extract')) return json({ ...extraction, positionTitle: null, warnings: ['Position title was not available.'] })
      return catalogResponse(url)
    })
    renderForm()

    fireEvent.paste(screen.getByRole('textbox', { name: 'Paste job posting URL' }), { clipboardData: { getData: () => 'https://example.com/partial' } })

    expect(await screen.findByText('Some job details were imported')).toBeInTheDocument()
    expect(screen.getByText('Position title was not available.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Save application' })).toBeEnabled()
  })
})
