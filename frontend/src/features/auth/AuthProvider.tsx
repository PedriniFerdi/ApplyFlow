import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, type ReactNode } from 'react'
import { ApiProblem } from '@/lib/api-client'
import type { CurrentUser } from '@/types/api'
import { getCurrentUser, signIn, signOut } from './api'
import { AuthContext, authQueryKey } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const currentUser = useQuery({
    queryKey: authQueryKey,
    queryFn: async () => {
      try {
        return await getCurrentUser()
      } catch (error) {
        if (error instanceof ApiProblem && error.status === 401) return null
        throw error
      }
    },
    retry: false,
    staleTime: 60_000,
  })

  const clearIdentityCache = useCallback((nextUser: CurrentUser | null) => {
    queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' })
    queryClient.setQueryData(authQueryKey, nextUser)
  }, [queryClient])

  const login = useCallback(async (email: string, password: string, rememberMe: boolean) => {
    const user = await signIn(email, password, rememberMe)
    clearIdentityCache(user)
    return user
  }, [clearIdentityCache])

  const logout = useCallback(async () => {
    try {
      await signOut()
    } finally {
      clearIdentityCache(null)
    }
  }, [clearIdentityCache])

  const { refetch } = currentUser
  const refresh = useCallback(async () => {
    const result = await refetch()
    return result.data ?? null
  }, [refetch])

  useEffect(() => {
    const onUnauthorized = () => clearIdentityCache(null)
    window.addEventListener('applyflow:unauthorized', onUnauthorized)
    return () => window.removeEventListener('applyflow:unauthorized', onUnauthorized)
  }, [clearIdentityCache])

  return <AuthContext.Provider value={{
    user: currentUser.data ?? null,
    isLoading: currentUser.isLoading,
    login,
    logout,
    refresh,
  }}>{children}</AuthContext.Provider>
}
