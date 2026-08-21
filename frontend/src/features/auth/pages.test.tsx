import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { clearCsrfToken } from '@/lib/api-client'
import { googleLoginUrl } from './api'
import { AuthProvider } from './AuthProvider'

const currentUser = { id: 7, fullName: 'Ferdi Example', email: 'ferdi@example.com', emailVerified: true, authenticationMethods: ['PASSWORD'] }

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderRoute(route: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><AuthProvider><App /></AuthProvider></MemoryRouter></QueryClientProvider>)
}

describe('authentication pages', () => {
  beforeEach(() => clearCsrfToken())
  afterEach(() => vi.restoreAllMocks())

  it('redirects an anonymous visitor from product routes to sign in', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute('/applications')
    expect(await screen.findByRole('heading', { name: 'Sign in to ApplyFlow' })).toBeInTheDocument()
  })

  it('renders the required sign-up controls with Google and without GitHub', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute('/sign-up')
    expect(await screen.findByRole('heading', { name: 'Create your space' })).toBeInTheDocument()
    expect(screen.getByLabelText('Full name')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    expect(screen.getByLabelText('Confirm password')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Continue with Google' })).toHaveAttribute('href', googleLoginUrl)
    expect(screen.queryByText(/github/i)).not.toBeInTheDocument()
  })

  it('submits registration after obtaining a CSRF token and shows the generic outcome', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json({ detail: 'Authentication required' }, 401)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/register')) {
        expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe('csrf-token')
        return json({ message: 'Generic response' }, 202)
      }
      return json([])
    })
    renderRoute('/sign-up')
    await user.type(await screen.findByLabelText('Full name'), 'Ferdi Example')
    await user.type(screen.getByLabelText('Email'), 'ferdi@example.com')
    await user.type(screen.getByLabelText('Password'), 'a-secure-password')
    await user.type(screen.getByLabelText('Confirm password'), 'a-secure-password')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByText('Check your inbox')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/auth/register'))).toBe(true)
  })

  it('sends form login with remember me and enters the protected application', async () => {
    const user = userEvent.setup()
    let authenticated = false
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return authenticated ? json(currentUser) : json({ detail: 'Authentication required' }, 401)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/login')) {
        authenticated = true
        expect(init?.body?.toString()).toContain('rememberMe=true')
        return json(currentUser)
      }
      if (url.includes('/applications?')) return json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/sign-in')
    await user.type(await screen.findByLabelText('Email'), 'ferdi@example.com')
    await user.type(screen.getByLabelText('Password'), 'a-secure-password')
    await user.click(screen.getByLabelText('Remember me'))
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/auth/login'))).toBe(true))
  })

  it('finalizes a successful OAuth callback once while React Query deduplicates the session request', async () => {
    let currentUserCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) {
        currentUserCalls++
        return json(currentUser)
      }
      if (input.toString().includes('/applications?')) return json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
    await waitFor(() => expect(currentUserCalls).toBe(1))
  })

  it('finalizes a failed OAuth callback once while React Query deduplicates the session request', async () => {
    let currentUserCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) {
        currentUserCalls++
        return json({ detail: 'Authentication required' }, 401)
      }
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('alert')).toHaveTextContent('Google sign-in completed without a valid ApplyFlow session.')
    await waitFor(() => expect(currentUserCalls).toBe(1))
  })
})
