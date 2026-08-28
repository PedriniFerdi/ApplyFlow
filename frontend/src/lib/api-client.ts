import type { ProblemDetails } from '@/types/api'

const BACKEND_BASE_URL = (import.meta.env.VITE_BACKEND_BASE_URL ?? '').replace(/\/$/, '')
const API_BASE_URL = `${BACKEND_BASE_URL}/api`
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

let csrfToken: { token: string; headerName: string } | null = null
let csrfPromise: Promise<{ token: string; headerName: string }> | null = null
let csrfGeneration = 0

export const sessionGeneration = () => csrfGeneration
export function requireSession(generation: number | undefined) {
  if (generation !== csrfGeneration) throw new DOMException('Session changed', 'AbortError')
}

export function backendUrl(path: string) {
  return `${BACKEND_BASE_URL}${path}`
}

export function clearCsrfToken() {
  csrfGeneration += 1
  csrfToken = null
  csrfPromise = null
}

export class ApiProblem extends Error {
  readonly status: number
  readonly title: string
  readonly errors: Record<string, string>

  constructor(problem: ProblemDetails, fallbackStatus: number) {
    super(problem.detail || 'The request could not be completed')
    this.name = 'ApiProblem'
    this.status = problem.status ?? fallbackStatus
    this.title = problem.title ?? 'Request failed'
    this.errors = problem.errors ?? {}
  }
}

interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
  form?: URLSearchParams
}

export async function prefetchCsrfToken() {
  if (csrfToken) return csrfToken
  if (!csrfPromise) {
    const generation = csrfGeneration
    csrfPromise = fetch(`${API_BASE_URL}/auth/csrf`, {
      credentials: 'include',
      headers: { Accept: 'application/json' },
    }).then(async (response) => {
      if (!response.ok) throw new ApiProblem({ detail: 'Unable to establish a secure session.' }, response.status)
      const token = await response.json() as { token: string; headerName: string }
      requireSession(generation)
      csrfToken = token
      return token
    }).finally(() => { if (generation === csrfGeneration) csrfPromise = null })
  }
  return csrfPromise
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const generation = sessionGeneration()
  const { body, form, ...requestOptions } = options
  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json, application/problem+json')
  if (body !== undefined) headers.set('Content-Type', 'application/json')
  if (form !== undefined) headers.set('Content-Type', 'application/x-www-form-urlencoded')

  let response: Response
  try {
    if (UNSAFE_METHODS.has(method)) {
      const csrf = await prefetchCsrfToken()
      headers.set(csrf.headerName, csrf.token)
    }
    requireSession(generation)
    response = await fetch(`${API_BASE_URL}${path}`, {
      ...requestOptions,
      method,
      headers,
      credentials: 'include',
      body: form ?? (body === undefined ? undefined : JSON.stringify(body)),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiProblem({ title: 'Network error', detail: 'Unable to reach the ApplyFlow API.' }, 0)
  }

  requireSession(generation)
  if (!response.ok) {
    let problem: ProblemDetails = {}
    try {
      problem = (await response.json()) as ProblemDetails
    } catch {
      problem = { detail: `The server returned status ${response.status}.` }
    }
    requireSession(generation)
    if (response.status === 401 && path !== '/auth/me') {
      window.dispatchEvent(new Event('applyflow:unauthorized'))
    }
    throw new ApiProblem(problem, response.status)
  }

  if (response.status === 204) return undefined as T
  const data = await response.json() as T
  requireSession(generation)
  return data
}
