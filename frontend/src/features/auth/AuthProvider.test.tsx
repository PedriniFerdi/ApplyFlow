import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCsrfToken } from '@/lib/api-client'
import { useAuth } from './auth-context'
import { AuthProvider } from './AuthProvider'

const userA = { id: 1, fullName: 'User A', email: 'a@example.com', emailVerified: true, authenticationMethods: ['PASSWORD'] }
const userB = { ...userA, id: 2, fullName: 'User B', email: 'b@example.com' }
const applicationKey = ['applications', 'list', { page: 0 }] as const

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function AuthProbe() {
  const session = useAuth()
  return <div>
    <p data-testid="session-status">{session.status}</p>
    <p data-testid="session-user">{session.user?.email ?? 'none'}</p>
    <button type="button" onClick={() => void session.retrySession()}>Retry</button>
    <button type="button" onClick={() => void session.logout().catch(() => undefined)}>Logout</button>
  </div>
}

function renderProvider() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return { client, ...render(<QueryClientProvider client={client}><AuthProvider><AuthProbe /></AuthProvider></QueryClientProvider>) }
}

describe('AuthProvider session transitions', () => {
  afterEach(() => { clearCsrfToken(); vi.restoreAllMocks() })

  it('keeps session loading distinct while bootstrap is pending', async () => {
    let resolveSession!: (response: Response) => void
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise((resolve) => { resolveSession = resolve }))
    renderProvider()
    expect(screen.getByTestId('session-status')).toHaveTextContent('loading')
    resolveSession(json(userA))
    expect(await screen.findByText('authenticated')).toBeInTheDocument()
  })

  it('treats only an explicit bootstrap 401 as anonymous', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderProvider()
    expect(await screen.findByText('anonymous')).toBeInTheDocument()
  })

  it('preserves user-scoped queries when retry confirms the same identity', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => json(userA))
    const { client } = renderProvider()
    expect(await screen.findByText('authenticated')).toBeInTheDocument()
    client.setQueryData(applicationKey, { owner: 'A' })
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(screen.getByTestId('session-user')).toHaveTextContent('a@example.com'))
    expect(client.getQueryData(applicationKey)).toEqual({ owner: 'A' })
  })

  it('clears user-scoped queries before accepting a different identity', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(json(userA)).mockResolvedValueOnce(json(userB))
    const { client } = renderProvider()
    expect(await screen.findByText('authenticated')).toBeInTheDocument()
    client.setQueryData(applicationKey, { owner: 'A' })
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(screen.getByTestId('session-user')).toHaveTextContent('b@example.com'))
    expect(client.getQueryData(applicationKey)).toBeUndefined()
  })

  it('clears identity and user-scoped queries after a protected unauthorized event', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(userA))
    const { client } = renderProvider()
    expect(await screen.findByText('authenticated')).toBeInTheDocument()
    client.setQueryData(applicationKey, { owner: 'A' })
    act(() => window.dispatchEvent(new Event('applyflow:unauthorized')))
    await waitFor(() => expect(screen.getByTestId('session-status')).toHaveTextContent('anonymous'))
    expect(client.getQueryData(applicationKey)).toBeUndefined()
  })

  it('clears identity and user-scoped queries when logout fails', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json(userA)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/logout')) return json({ detail: 'Service unavailable' }, 503)
      return json([])
    })
    const { client } = renderProvider()
    expect(await screen.findByText('authenticated')).toBeInTheDocument()
    client.setQueryData(applicationKey, { owner: 'A' })
    await user.click(screen.getByRole('button', { name: 'Logout' }))
    await waitFor(() => expect(screen.getByTestId('session-status')).toHaveTextContent('anonymous'))
    expect(client.getQueryData(applicationKey)).toBeUndefined()
  })
})
