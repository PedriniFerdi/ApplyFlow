import { createContext, useContext } from 'react'
import type { CurrentUser } from '@/types/api'

export type AuthSessionState =
  | { status: 'loading'; user: null }
  | { status: 'authenticated'; user: CurrentUser }
  | { status: 'anonymous'; user: null }
  | { status: 'unavailable'; user: null; isRetrying: boolean }

interface AuthActions {
  login: (email: string, password: string, rememberMe: boolean) => Promise<CurrentUser>
  logout: () => Promise<void>
  retrySession: () => Promise<void>
}

export type AuthContextValue = AuthSessionState & AuthActions

export const AuthContext = createContext<AuthContextValue | null>(null)
export const authQueryKey = ['auth', 'me'] as const

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
