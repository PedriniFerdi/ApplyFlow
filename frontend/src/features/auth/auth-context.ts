import { createContext, useContext } from 'react'
import type { CurrentUser } from '@/types/api'

export interface AuthContextValue {
  user: CurrentUser | null
  isLoading: boolean
  login: (email: string, password: string, rememberMe: boolean) => Promise<CurrentUser>
  logout: () => Promise<void>
  refresh: () => Promise<CurrentUser | null>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
export const authQueryKey = ['auth', 'me'] as const

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
