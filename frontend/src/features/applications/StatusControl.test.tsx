import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '@/lib/api-client'
import { StatusControl } from './StatusControl'

function renderControl() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><StatusControl id={42} status="BOOKMARKED" appliedDate={null} /></QueryClientProvider>)
}

describe('StatusControl', () => {
  beforeEach(() => clearCsrfToken())
  afterEach(() => vi.restoreAllMocks())

  it('requires an applied date before leaving bookmarked and sends the explicit transition', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/csrf')
      ? new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }), { status: 200 })
      : new Response(JSON.stringify({ id: 42 }), { status: 200 }))
    renderControl()

    await user.selectOptions(screen.getByLabelText('Status'), 'APPLIED')
    expect(screen.getByRole('button', { name: 'Update' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Applied date'), { target: { value: '2026-08-01' } })
    await user.click(screen.getByRole('button', { name: 'Update' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const request = fetchMock.mock.calls[1][1] as RequestInit
    expect(JSON.parse(String(request.body))).toEqual({ status: 'APPLIED', appliedDate: '2026-08-01' })
  })

  it('shows a safe server error when a transition fails', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => input.toString().endsWith('/auth/csrf')
      ? new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }), { status: 200 })
      : new Response(JSON.stringify({ status: 400, detail: 'Applied date is invalid' }), { status: 400 }))
    renderControl()
    await user.selectOptions(screen.getByLabelText('Status'), 'APPLIED')
    fireEvent.change(screen.getByLabelText('Applied date'), { target: { value: '2026-08-01' } })
    await user.click(screen.getByRole('button', { name: 'Update' }))
    expect(await screen.findByText('Applied date is invalid')).toBeInTheDocument()
  })
})
