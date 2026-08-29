import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { clearCsrfToken } from '@/lib/api-client'
import { googleLoginUrl } from './api'
import { AuthProvider } from './AuthProvider'

const currentUser = { id: 7, fullName: 'Ferdi Example', email: 'ferdi@example.com', emailVerified: true, authenticationMethods: ['PASSWORD'] }
const oauthReturnPathKey = 'applyflow:oauth-return-path'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function renderRoute(route: string | { pathname: string; state: { from: string } }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><AuthProvider><App /></AuthProvider></MemoryRouter></QueryClientProvider>)
}

describe('authentication pages', () => {
  beforeEach(() => {
    clearCsrfToken()
    sessionStorage.clear()
  })
  afterEach(() => vi.restoreAllMocks())

  it('redirects an anonymous visitor from product routes to sign in', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute('/applications')
    const heading = await screen.findByRole('heading', { name: 'Sign in to ApplyFlow' })
    await waitFor(() => expect(heading).toHaveFocus())
    expect(heading).toHaveAttribute('tabindex', '-1')
  })

  it('keeps protected content behind a labelled loading state during session bootstrap', async () => {
    let resolveSession!: (response: Response) => void
    vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise((resolve) => { resolveSession = resolve }))
    renderRoute('/applications')
    expect(screen.getByRole('status')).toHaveTextContent('Loading your session')
    expect(screen.queryByRole('heading', { name: 'Applications' })).not.toBeInTheDocument()
    resolveSession(json({ detail: 'Authentication required' }, 401))
    expect(await screen.findByRole('heading', { name: 'Sign in to ApplyFlow' })).toBeInTheDocument()
  })

  it.each([
    ['server failure', () => Promise.resolve(json({ detail: 'Unavailable' }, 503))],
    ['network failure', () => Promise.reject(new TypeError('offline'))],
  ])('shows retryable session unavailable UI without exposing protected content after %s', async (_, response) => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(response)
    renderRoute('/applications')
    expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t verify your session')
    expect(screen.getByRole('button', { name: 'Retry session check' })).toBeEnabled()
    expect(screen.queryByRole('heading', { name: 'Applications' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in to ApplyFlow' })).not.toBeInTheDocument()
  })

  it('disables session retry while pending and recovers the protected route', async () => {
    const user = userEvent.setup()
    let resolveRetry!: (response: Response) => void
    const retryResponse = new Promise<Response>((resolve) => { resolveRetry = resolve })
    let sessionCalls = 0
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return ++sessionCalls === 1 ? json({ detail: 'Unavailable' }, 503) : retryResponse
      if (url.includes('/applications?')) return json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/applications')
    await user.click(await screen.findByRole('button', { name: 'Retry session check' }))
    expect(await screen.findByRole('button', { name: 'Checking session…' })).toBeDisabled()
    resolveRetry(json(currentUser))
    await waitFor(() => {
      expect(fetchMock.mock.calls.some(([input]) => input.toString().includes('/applications?'))).toBe(true)
      expect(screen.getByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
    }, { timeout: 5_000 })
  }, 10_000)

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

  it('associates password confirmation mismatch feedback with its control', async () => {
    const user = userEvent.setup()
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute('/sign-up')

    await user.type(await screen.findByLabelText('Password'), 'a-secure-password')
    const confirmation = screen.getByLabelText('Confirm password')
    await user.type(confirmation, 'a-different-password')

    const feedback = screen.getByText('Passwords do not match.')
    expect(confirmation).toHaveAttribute('aria-invalid', 'true')
    expect(confirmation).toHaveAttribute('aria-describedby', 'passwordConfirmation-error')
    expect(feedback).toHaveAttribute('id', 'passwordConfirmation-error')
    expect(feedback).toHaveAttribute('role', 'status')
  })

  it('resends verification from the registration success state using the submitted email', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json({ detail: 'Authentication required' }, 401)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/register')) {
        expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe('csrf-token')
        return json({ message: 'Generic response' }, 202)
      }
      if (url.endsWith('/auth/email-verification/resend')) {
        expect(init?.method).toBe('POST')
        expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe('csrf-token')
        expect(JSON.parse(String(init?.body))).toEqual({ email: 'ferdi@example.com' })
        return json({ message: 'If an eligible account exists, an email will arrive with the next step.' }, 202)
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
    expect(screen.getByLabelText('Email')).toHaveValue('ferdi@example.com')
    await user.click(screen.getByRole('button', { name: 'Resend verification email' }))
    expect(await screen.findByText('If an eligible account exists, an email will arrive with the next step.')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/auth/register'))).toBe(true)
  })

  it('offers verification resend and sign-in exits when the link token is missing', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json({ detail: 'Authentication required' }, 401)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/email-verification/resend')) return json({ message: 'Generic recovery response' }, 202)
      return json([])
    })
    renderRoute('/verify-email')
    expect(await screen.findByRole('alert')).toHaveTextContent('This verification link is missing its token.')
    await user.type(screen.getByLabelText('Email'), 'pending@example.com')
    await user.click(screen.getByRole('button', { name: 'Resend verification email' }))
    expect(await screen.findByText('Generic recovery response')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/sign-in')
    expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/auth/email-verification/resend'))).toBe(true)
  })

  it('prevents editing or resubmitting while verification resend is pending', async () => {
    const user = userEvent.setup()
    let resolveResend!: (response: Response) => void
    const resendResponse = new Promise<Response>((resolve) => { resolveResend = resolve })
    let resendCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json({ detail: 'Authentication required' }, 401)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/email-verification/resend')) {
        resendCalls++
        return resendResponse
      }
      return json([])
    })
    renderRoute('/verify-email')
    const email = await screen.findByLabelText('Email')
    await user.type(email, 'pending@example.com')
    await user.click(screen.getByRole('button', { name: 'Resend verification email' }))
    await waitFor(() => expect(resendCalls).toBe(1))
    expect(email).toBeDisabled()
    fireEvent.change(email, { target: { value: 'second@example.com' } })
    fireEvent.submit(email.closest('form')!)
    expect(resendCalls).toBe(1)
    resolveResend(json({ message: 'Generic recovery response' }, 202))
    expect(await screen.findByText('Generic recovery response')).toBeInTheDocument()
  })

  it('offers the same resend recovery after an invalid or expired verification link', async () => {
    const user = userEvent.setup()
    let confirmationCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json({ detail: 'Authentication required' }, 401)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/email-verification/confirm')) {
        confirmationCalls++
        return json({ detail: 'The account link is invalid or has expired' }, 400)
      }
      if (url.endsWith('/auth/email-verification/resend')) return json({ message: 'Generic recovery response' }, 202)
      return json([])
    })
    renderRoute('/verify-email?token=used-token')
    expect(await screen.findByRole('alert')).toHaveTextContent('The account link is invalid or has expired')
    await user.type(screen.getByLabelText('Email'), 'pending@example.com')
    await user.click(screen.getByRole('button', { name: 'Resend verification email' }))
    expect(await screen.findByText('Generic recovery response')).toBeInTheDocument()
    expect(confirmationCalls).toBe(1)
  })

  it('exposes verification resend from sign in', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute('/sign-in')
    expect(await screen.findByRole('link', { name: 'Resend verification email' })).toHaveAttribute('href', '/verify-email')
  })

  it('confirms a valid verification token once and offers sign in', async () => {
    let confirmationCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return json({ detail: 'Authentication required' }, 401)
      if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
      if (url.endsWith('/auth/email-verification/confirm')) {
        confirmationCalls++
        return new Response(null, { status: 204 })
      }
      return json([])
    })
    renderRoute('/verify-email?token=valid-token')
    expect(await screen.findByText('Email verified')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Continue to sign in' })).toHaveAttribute('href', '/sign-in')
    expect(confirmationCalls).toBe(1)
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
    sessionStorage.setItem(oauthReturnPathKey, JSON.stringify({ path: '/tracker', createdAt: Date.now() }))
    renderRoute('/sign-in')
    await user.type(await screen.findByLabelText('Email'), 'ferdi@example.com')
    await user.type(screen.getByLabelText('Password'), 'a-secure-password')
    await user.click(screen.getByLabelText('Remember me'))
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
    expect(sessionStorage.getItem(oauthReturnPathKey)).toBeNull()
    await waitFor(() => expect(fetchMock.mock.calls.some(([input]) => input.toString().endsWith('/auth/login'))).toBe(true))
  })

  it('offers generic Google retry and password recovery after a provider failure without exposing diagnostics', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute('/sign-in?oauthError=oauth_failed&error_description=client-secret-leaked')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Google sign-in could not be completed.')
    expect(screen.getByRole('link', { name: 'Try Google again' })).toHaveAttribute('href', googleLoginUrl)
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
    expect(screen.queryByText(/client-secret-leaked/)).not.toBeInTheDocument()
  })

  it('only treats the allowlisted OAuth failure code as a provider failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute('/sign-in?oauthError=access_denied')
    expect(await screen.findByRole('link', { name: 'Continue with Google' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('preserves a validated protected return path when Google sign-in starts', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(json({ detail: 'Authentication required' }, 401))
    renderRoute({ pathname: '/sign-in', state: { from: '/tracker?view=active#interviews' } })
    const googleLink = await screen.findByRole('link', { name: 'Continue with Google' })
    googleLink.addEventListener('click', (event) => event.preventDefault(), { once: true })
    fireEvent.click(googleLink)
    expect(JSON.parse(sessionStorage.getItem(oauthReturnPathKey)!)).toMatchObject({ path: '/tracker?view=active#interviews' })
  })

  it('consumes a valid OAuth return intent once after callback success', async () => {
    let currentUserCalls = 0
    sessionStorage.setItem(oauthReturnPathKey, JSON.stringify({ path: '/tracker?view=active#interviews', createdAt: Date.now() }))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) {
        currentUserCalls++
        return json(currentUser)
      }
      if (input.toString().includes('/applications?')) return json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('heading', { name: 'Tracker' })).toBeInTheDocument()
    expect(sessionStorage.getItem(oauthReturnPathKey)).toBeNull()
    await waitFor(() => expect(currentUserCalls).toBe(1))
  })

  it('rejects an external OAuth return intent and falls back to applications', async () => {
    sessionStorage.setItem(oauthReturnPathKey, JSON.stringify({ path: '//evil.example/account', createdAt: Date.now() }))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) return json(currentUser)
      if (input.toString().includes('/applications?')) return json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
    expect(sessionStorage.getItem(oauthReturnPathKey)).toBeNull()
  })

  it('rejects a stale OAuth return intent and falls back to applications', async () => {
    sessionStorage.setItem(oauthReturnPathKey, JSON.stringify({ path: '/tracker', createdAt: Date.now() - 11 * 60 * 1000 }))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) return json(currentUser)
      if (input.toString().includes('/applications?')) return json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
  })

  it('offers runnable exits when the OAuth callback has no session', async () => {
    let currentUserCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) {
        currentUserCalls++
        return json({ detail: 'Authentication required' }, 401)
      }
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('alert')).toHaveTextContent('Google sign-in could not be completed.')
    expect(screen.getByRole('link', { name: 'Try Google again' })).toHaveAttribute('href', googleLoginUrl)
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/sign-in')
    await waitFor(() => expect(currentUserCalls).toBe(1))
  })

  it('keeps OAuth recovery intent through an outage and consumes it after session retry succeeds', async () => {
    const user = userEvent.setup()
    let sessionCalls = 0
    sessionStorage.setItem(oauthReturnPathKey, JSON.stringify({ path: '/tracker?view=active#interviews', createdAt: Date.now() }))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      const url = input.toString()
      if (url.endsWith('/auth/me')) return ++sessionCalls === 1 ? json({ detail: 'Unavailable' }, 503) : json(currentUser)
      if (url.includes('/applications?')) return json({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t verify your session')
    expect(screen.queryByRole('link', { name: 'Try Google again' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry session check' }))
    expect(await screen.findByRole('heading', { name: 'Tracker' })).toBeInTheDocument()
    expect(sessionStorage.getItem(oauthReturnPathKey)).toBeNull()
  })

  it('shows WU-1.2 OAuth recovery only after retry proves the callback is anonymous', async () => {
    const user = userEvent.setup()
    let sessionCalls = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) return ++sessionCalls === 1
        ? json({ detail: 'Unavailable' }, 503)
        : json({ detail: 'Authentication required' }, 401)
      return json([])
    })
    renderRoute('/auth/callback')
    await user.click(await screen.findByRole('button', { name: 'Retry session check' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Google sign-in could not be completed.')
    expect(screen.getByRole('link', { name: 'Try Google again' })).toHaveAttribute('href', googleLoginUrl)
  })

  it('does not block OAuth callback success when session storage fails', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('Storage unavailable') })
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => { throw new Error('Storage unavailable') })
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (input.toString().endsWith('/auth/me')) return json(currentUser)
      if (input.toString().includes('/applications?')) return json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
      return json([])
    })
    renderRoute('/auth/callback')
    expect(await screen.findByRole('heading', { name: 'No applications yet' })).toBeInTheDocument()
  })
})
