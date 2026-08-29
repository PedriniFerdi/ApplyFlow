import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { clearCsrfToken, prefetchCsrfToken } from '@/lib/api-client'
import { AuthProvider } from './AuthProvider'
import { authQueryKey } from './auth-context'
import { accountExportUrl } from './api'
import { applicationKeys, catalogKeys, useCreateApplication, useCreateTechnology } from '@/features/applications/api'

const code = 'A'.repeat(43)
const account = { id: 7, fullName: 'Owner', email: 'owner@example.com', emailVerified: true, authenticationMethods: ['PASSWORD'] }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

function setup(method = 'PASSWORD', request = async () => json({ message: 'Request accepted.' }, 202), confirm = async () => new Response(null, { status: 204 })) {
  let deleted = false
  const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const url = input.toString()
    if (url.endsWith('/auth/me')) return deleted ? json({}, 401) : json({ ...account, authenticationMethods: [method] })
    if (url.endsWith('/auth/csrf')) return json({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' })
    expect(init?.credentials).toBe('include')
    expect(init?.method).toBe('POST')
    expect(new Headers(init?.headers).get('X-CSRF-TOKEN')).toBe('csrf-token')
    if (url.endsWith('/deletion/request')) return request()
    if (url.endsWith('/deletion/confirm')) {
      expect(JSON.parse(String(init?.body))).toEqual({ token: code, confirmation: 'DELETE' })
      const response = await confirm()
      deleted = response.status === 204
      return response
    }
    throw new Error(`Unexpected request: ${url}`)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[`/settings/security?token=${code}&confirmation=DELETE`]}>
    <AuthProvider><App /></AuthProvider>
  </MemoryRouter></QueryClientProvider>)
  return { client, fetchMock }
}

async function fillConfirmation() {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Deletion confirmation code'), code)
  await user.type(screen.getByLabelText('Type DELETE to confirm'), 'DELETE')
  return user
}

describe('account lifecycle settings', () => {
  afterEach(() => { clearCsrfToken(); sessionStorage.clear(); vi.restoreAllMocks() })

  it.each(['PASSWORD', 'GOOGLE'])('exposes native attachment download and guarded deletion for %s without automatic actions', async (method) => {
    const { fetchMock } = setup(method)
    const link = await screen.findByRole('link', { name: 'Download account JSON' })
    expect(link).toHaveAttribute('href', accountExportUrl)
    expect(link).toHaveAttribute('download', 'applyflow-account-export.json')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByLabelText('Deletion confirmation code')).toHaveValue('')
    expect(() => new RegExp((screen.getByLabelText('Deletion confirmation code') as HTMLInputElement).pattern, 'v')).not.toThrow()
    expect(screen.getByRole('button', { name: 'Permanently delete my account' })).toBeDisabled()
    expect(screen.getByText(/This cannot be undone/)).toBeInTheDocument()
    expect(screen.getByText(/Your browser handles the download/)).toBeInTheDocument()
    await userEvent.setup().click(screen.getByText('Account lifecycle policy'))
    expect(screen.getByText(/It is not Google reauthentication or MFA/)).toBeVisible()
    if (method === 'GOOGLE') expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.every(([url]) => url.toString().endsWith('/auth/me'))).toBe(true)
  })

  it('requests only a code, prevents pending duplicates, and requires exact DELETE', async () => {
    let finish!: (response: Response) => void
    const { fetchMock } = setup('GOOGLE', () => new Promise((resolve) => { finish = resolve }))
    const request = await screen.findByRole('button', { name: 'Request deletion code' })
    fireEvent.click(request)
    fireEvent.click(request)
    await waitFor(() => expect(request).toBeDisabled())
    expect(screen.getByLabelText('Deletion confirmation code')).toBeDisabled()
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    await act(async () => finish(json({ message: 'Request accepted.' }, 202)))
    expect(await screen.findByText(/Queue acceptance does not guarantee delivery/)).toBeVisible()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Deletion confirmation code'), code)
    await user.type(screen.getByLabelText('Type DELETE to confirm'), 'delete')
    expect(screen.getByRole('button', { name: 'Permanently delete my account' })).toBeDisabled()
    expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/deletion/request'))).toHaveLength(1)
    expect(fetchMock.mock.calls.some(([url]) => url.toString().endsWith('/deletion/confirm'))).toBe(false)
  })

  it('keeps the account and data after a failed confirmation without retrying', async () => {
    const { client, fetchMock } = setup('PASSWORD', undefined, async () => json({ detail: 'Code expired.' }, 400))
    const user = await fillConfirmation()
    client.setQueryData(['applications'], { private: 'data' })
    await user.click(screen.getByRole('button', { name: 'Permanently delete my account' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Code expired.')
    expect(client.getQueryData(authQueryKey)).toEqual(account)
    expect(client.getQueryData(['applications'])).toEqual({ private: 'data' })
    expect(screen.getByRole('heading', { name: 'Security' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Permanently delete my account' })).toBeEnabled()
    expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/deletion/confirm'))).toHaveLength(1)
  })

  it('shows request errors without submitting deletion or clearing the account', async () => {
    const { client, fetchMock } = setup('GOOGLE', async () => json({ detail: 'Please try again later.' }, 429))
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Request deletion code' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Please try again later.')
    expect(client.getQueryData(authQueryKey)).toMatchObject({ id: account.id })
    expect(fetchMock.mock.calls.some(([url]) => url.toString().endsWith('/deletion/confirm'))).toBe(false)
  })

  it.each(['application', 'technology'])('rejects a pending %s success callback after deletion instead of restoring private data', async (kind) => {
    const { client, fetchMock } = setup()
    const user = await fillConfirmation()
    const originalFetch = fetchMock.getMockImplementation()!
    const path = kind === 'application' ? '/api/applications' : '/api/technologies'
    fetchMock.mockImplementation((input, init) => input.toString().endsWith(path)
      ? Promise.resolve(json({ id: 99, name: 'Private technology', positionTitle: 'Private role' })) : originalFetch(input, init))
    let finish!: () => void
    client.getMutationCache().config.onSuccess = (data) => (data as { id?: number } | undefined)?.id === 99
      ? new Promise<void>((resolve) => { finish = resolve }) : undefined
    const hooks = renderHook(() => ({ application: useCreateApplication(), technology: useCreateTechnology() }), {
      wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    })
    let pending!: Promise<unknown>
    act(() => {
      pending = (kind === 'technology' ? hooks.result.current.technology.mutateAsync('Private technology')
        : hooks.result.current.application.mutateAsync({ companyId: 1, sourceId: 1, positionTitle: 'Private role', status: 'BOOKMARKED', workMode: 'REMOTE', technologyIds: [] })).catch((error: unknown) => error)
    })
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    const deleteButton = screen.getByRole('button', { name: 'Permanently delete my account' })
    await waitFor(() => expect(deleteButton).toBeEnabled())
    await user.click(deleteButton)
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/deletion/confirm'))).toHaveLength(1))
    expect(await screen.findByRole('heading', { name: 'Sign in to ApplyFlow' }, { timeout: 5_000 })).toBeInTheDocument()
    await act(async () => { finish(); expect(await pending).toMatchObject({ name: 'AbortError' }) })
    expect(client.getQueryData(applicationKeys.detail(99))).toBeUndefined()
    expect(client.getQueryData(catalogKeys.technologies)).toBeUndefined()
    expect(client.getQueryData(authQueryKey)).toBeNull()
    expect(fetchMock.mock.calls.some(([url]) => url.toString().endsWith('/auth/logout'))).toBe(false)
  })

  it('blocks duplicate confirmation and clears query, mutation, CSRF and OAuth state after 204 without logout', async () => {
    let finish!: (response: Response) => void
    const { client, fetchMock } = setup('GOOGLE', undefined, () => new Promise((resolve) => { finish = resolve }))
    await fillConfirmation()
    client.setQueryData(['applications'], { private: 'data' })
    client.setQueryData(['auth', 'other'], { private: 'data' })
    let finishQuery!: (data: string) => void
    void client.fetchQuery({ queryKey: ['late-private'], queryFn: () => new Promise<string>((resolve) => { finishQuery = resolve }) }).catch(() => undefined)
    await client.getMutationCache().build(client, { mutationFn: async () => 'private data' }).execute(undefined)
    sessionStorage.setItem('applyflow:oauth-return-path', '/applications')
    const button = screen.getByRole('button', { name: 'Permanently delete my account' })
    fireEvent.click(button)
    fireEvent.click(button)
    await waitFor(() => expect(button).toBeDisabled())
    expect(screen.getByLabelText('Type DELETE to confirm')).toBeDisabled()
    expect(client.getQueryData(['applications'])).toBeDefined()
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    await act(async () => finish(new Response(null, { status: 204 })))
    expect(await screen.findByRole('heading', { name: 'Sign in to ApplyFlow' })).toBeInTheDocument()
    expect(client.getQueryCache().getAll().map((query) => query.queryKey)).toEqual([authQueryKey])
    expect(client.getQueryData(authQueryKey)).toBeNull()
    expect(client.getMutationCache().getAll().every((mutation) => mutation.state.data === undefined && mutation.state.variables === undefined)).toBe(true)
    expect(sessionStorage.getItem('applyflow:oauth-return-path')).toBeNull()
    await act(async () => finishQuery('late private data'))
    expect(client.getQueryData(['late-private'])).toBeUndefined()
    await prefetchCsrfToken()
    expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/auth/csrf'))).toHaveLength(2)
    expect(fetchMock.mock.calls.filter(([url]) => url.toString().endsWith('/deletion/confirm'))).toHaveLength(1)
    expect(fetchMock.mock.calls.some(([url]) => url.toString().endsWith('/auth/logout'))).toBe(false)
  })
})
