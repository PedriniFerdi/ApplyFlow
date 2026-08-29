import { apiRequest, backendUrl, clearCsrfToken } from '@/lib/api-client'
import type { CurrentUser, GenericMessage } from '@/types/api'

export interface RegistrationInput {
  fullName: string
  email: string
  password: string
  passwordConfirmation: string
}

export interface PasswordResetInput {
  token: string
  password: string
  passwordConfirmation: string
}

export interface ChangePasswordInput {
  currentPassword: string
  password: string
  passwordConfirmation: string
}

export const getCurrentUser = () => apiRequest<CurrentUser>('/auth/me')
export const register = (input: RegistrationInput) => apiRequest<GenericMessage>('/auth/register', { method: 'POST', body: input })
export const verifyEmail = (token: string) => apiRequest<void>('/auth/email-verification/confirm', { method: 'POST', body: { token } })
export const resendVerification = (email: string) => apiRequest<GenericMessage>('/auth/email-verification/resend', { method: 'POST', body: { email } })
export const forgotPassword = (email: string) => apiRequest<GenericMessage>('/auth/password/forgot', { method: 'POST', body: { email } })
export const resetPassword = (input: PasswordResetInput) => apiRequest<void>('/auth/password/reset', { method: 'POST', body: input })
export const changePassword = (input: ChangePasswordInput) => apiRequest<void>('/auth/password', { method: 'PUT', body: input })
export const requestPasswordSetup = () => apiRequest<GenericMessage>('/auth/password/setup', { method: 'POST' })
export const accountExportUrl = backendUrl('/api/account/export')
export const requestAccountDeletion = () => apiRequest<GenericMessage>('/account/deletion/request', { method: 'POST' })
export const confirmAccountDeletion = (input: { token: string; confirmation: string }) => apiRequest<void>('/account/deletion/confirm', { method: 'POST', body: input })

export async function signIn(email: string, password: string, rememberMe: boolean) {
  const form = new URLSearchParams({ email, password })
  if (rememberMe) form.set('rememberMe', 'true')
  const user = await apiRequest<CurrentUser>('/auth/login', { method: 'POST', form })
  clearCsrfToken()
  return user
}

export async function signOut() {
  try {
    await apiRequest<void>('/auth/logout', { method: 'POST' })
  } finally {
    clearCsrfToken()
  }
}

export const googleLoginUrl = backendUrl('/oauth2/authorization/google')
