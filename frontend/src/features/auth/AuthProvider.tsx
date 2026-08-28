import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { ApiProblem, clearCsrfToken } from '@/lib/api-client'
import type { CurrentUser } from '@/types/api'
import { getCurrentUser, signIn, signOut } from './api'
import { AuthContext, authQueryKey } from './auth-context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [isRetrying, setIsRetrying] = useState(false)
  const clearUserQueriesForIdentityChange = useCallback((nextUser: CurrentUser | null) => {
    const previousUser = queryClient.getQueryData<CurrentUser | null>(authQueryKey)
    if (previousUser !== undefined && (previousUser?.id ?? null) !== (nextUser?.id ?? null)) {
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' })
    }
  }, [queryClient])

  const currentUser = useQuery({
    queryKey: authQueryKey,
    queryFn: async () => {
      try {
        const user = await getCurrentUser()
        clearUserQueriesForIdentityChange(user)
        return user
      } catch (error) {
        if (error instanceof ApiProblem && error.status === 401) {
          clearUserQueriesForIdentityChange(null)
          return null
        }
        throw error
      }
    },
    retry: false,
    staleTime: 60_000,
  })

  const setIdentity = useCallback((nextUser: CurrentUser | null) => {
    clearUserQueriesForIdentityChange(nextUser)
    queryClient.setQueryData(authQueryKey, nextUser)
  }, [clearUserQueriesForIdentityChange, queryClient])

  const login = useCallback(async (email: string, password: string, rememberMe: boolean) => {
    const user = await signIn(email, password, rememberMe)
    setIdentity(user)
    return user
  }, [setIdentity])

  const logout = useCallback(async () => {
    try {
      await signOut()
    } finally {
      setIdentity(null)
    }
  }, [setIdentity])

  const { refetch } = currentUser
  const completeAccountDeletion = useCallback(async () => {
    await queryClient.cancelQueries()
    queryClient.setQueryData(authQueryKey, null)
    const sessionQuery = queryClient.getQueryCache().find({ queryKey: authQueryKey, exact: true })
    queryClient.removeQueries({ predicate: (query) => query !== sessionQuery })
    queryClient.getMutationCache().clear()
    clearCsrfToken()
    setIsRetrying(false)
    try {
      sessionStorage.removeItem('applyflow:oauth-return-path')
    } catch {
      // In-memory account cleanup must still finish when storage is unavailable.
    }
  }, [queryClient])

  const retrySession = useCallback(async () => {
    if (isRetrying) return
    setIsRetrying(true)
    try {
      await refetch()
    } finally {
      setIsRetrying(false)
    }
  }, [isRetrying, refetch])

  useEffect(() => {
    const onUnauthorized = () => setIdentity(null)
    window.addEventListener('applyflow:unauthorized', onUnauthorized)
    return () => window.removeEventListener('applyflow:unauthorized', onUnauthorized)
  }, [setIdentity])

  const session = currentUser.isPending
    ? isRetrying
      ? { status: 'unavailable', user: null, isRetrying: true } as const
      : { status: 'loading', user: null } as const
    : currentUser.isError
      ? { status: 'unavailable', user: null, isRetrying } as const
      : currentUser.data
        ? { status: 'authenticated', user: currentUser.data } as const
        : { status: 'anonymous', user: null } as const

  return <AuthContext.Provider value={{
    ...session,
    login,
    logout,
    completeAccountDeletion,
    retrySession,
  }}>{children}</AuthContext.Provider>
}
