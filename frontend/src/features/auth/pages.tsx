import { useMutation } from '@tanstack/react-query'
import { Check, Eye, EyeOff, LockKeyhole, Mail } from 'lucide-react'
import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import landscape from '@/assets/applyflow-landscape.png'
import { Button, FieldError, Input, Label } from '@/components/ui'
import { ApiProblem } from '@/lib/api-client'
import { useAuth } from './auth-context'
import {
  changePassword,
  forgotPassword,
  googleLoginUrl,
  register,
  requestPasswordSetup,
  resendVerification,
  resetPassword,
  verifyEmail,
} from './api'

function AuthLayout({ eyebrow, title, description, children }: {
  eyebrow: string
  title: string
  description: string
  children: ReactNode
}) {
  return <main className="min-h-[100dvh] bg-[#eeeeec] p-3 sm:p-5 lg:p-7">
    <div className="mx-auto grid min-h-[calc(100dvh-1.5rem)] max-w-[1510px] overflow-hidden rounded-[22px] border border-black/[0.07] bg-white shadow-[0_24px_70px_rgba(25,25,23,.13)] sm:min-h-[calc(100dvh-2.5rem)] lg:min-h-[calc(100dvh-3.5rem)] lg:grid-cols-[minmax(360px,.92fr)_minmax(520px,1.08fr)]">
      <section className="relative hidden min-h-full overflow-hidden lg:block">
        <img src={landscape} alt="" className="absolute inset-0 h-full w-full object-cover object-[62%_center]" />
        <div className="absolute inset-x-0 top-0 flex items-center justify-between p-10 text-[#181918]">
          <Link to="/sign-in" className="text-[18px] font-semibold tracking-[-0.035em]">ApplyFlow</Link>
          <span className="text-xs font-medium uppercase tracking-[0.18em]">Make the next move</span>
        </div>
        <p className="absolute bottom-11 left-10 max-w-[24ch] text-[28px] font-medium leading-[1.08] tracking-[-0.04em] text-[#171817]">
          Your search, held together with clarity.
        </p>
      </section>

      <section className="flex min-h-full items-center justify-center px-5 py-10 sm:px-10 lg:px-16 xl:px-24">
        <div className="w-full max-w-[430px]">
          <Link to="/sign-in" className="mb-14 inline-block text-[18px] font-semibold tracking-[-0.035em] lg:hidden">ApplyFlow</Link>
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-[#70706d]">{eyebrow}</p>
          <h1 className="mt-3 text-[clamp(2.35rem,5vw,3.55rem)] font-medium leading-[.98] tracking-[-0.055em]">{title}</h1>
          <p className="mt-4 max-w-[42ch] text-[15px] leading-6 text-muted-foreground">{description}</p>
          <div className="mt-9">{children}</div>
        </div>
      </section>
    </div>
  </main>
}

function PasswordInput({ id, value, onChange, autoComplete }: {
  id: string
  value: string
  onChange: (value: string) => void
  autoComplete: string
}) {
  const [visible, setVisible] = useState(false)
  return <div className="relative">
    <Input id={id} type={visible ? 'text' : 'password'} autoComplete={autoComplete} value={value} onChange={(event) => onChange(event.target.value)} className="min-h-12 pr-12" required />
    <button type="button" onClick={() => setVisible((current) => !current)} aria-label={visible ? 'Hide password' : 'Show password'} className="absolute inset-y-0 right-0 grid w-12 place-items-center text-muted-foreground transition hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring">
      {visible ? <EyeOff size={18} aria-hidden="true" /> : <Eye size={18} aria-hidden="true" />}
    </button>
  </div>
}

const OAUTH_RETURN_PATH_KEY = 'applyflow:oauth-return-path'
const OAUTH_RETURN_PATH_MAX_AGE_MS = 10 * 60 * 1000

function safeInternalPath(value: unknown) {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')) return null
  try {
    const url = new URL(value, window.location.origin)
    return url.origin === window.location.origin ? `${url.pathname}${url.search}${url.hash}` : null
  } catch {
    return null
  }
}

function readOAuthReturnPath() {
  try {
    const value = JSON.parse(sessionStorage.getItem(OAUTH_RETURN_PATH_KEY) ?? 'null') as { path?: unknown; createdAt?: unknown } | null
    const now = Date.now()
    if (!value || typeof value.createdAt !== 'number' || value.createdAt > now || now - value.createdAt > OAUTH_RETURN_PATH_MAX_AGE_MS) return null
    return safeInternalPath(value.path)
  } catch {
    return null
  }
}

function rememberOAuthReturnPath(path: string) {
  try {
    sessionStorage.setItem(OAUTH_RETURN_PATH_KEY, JSON.stringify({ path: safeInternalPath(path) ?? '/applications', createdAt: Date.now() }))
  } catch {
    // OAuth remains usable when browser storage is unavailable.
  }
}

function clearOAuthReturnPath() {
  try {
    sessionStorage.removeItem(OAUTH_RETURN_PATH_KEY)
  } catch {
    // Authentication must not depend on browser storage.
  }
}

function consumeOAuthReturnPath() {
  const path = readOAuthReturnPath()
  clearOAuthReturnPath()
  return path ?? '/applications'
}

function GoogleButton({ children = 'Continue with Google', returnTo = '/applications' }: { children?: ReactNode; returnTo?: string }) {
  return <a href={googleLoginUrl} onClick={() => rememberOAuthReturnPath(returnTo)} className="flex min-h-12 w-full items-center justify-center gap-3 rounded-[11px] border border-[#d6d6d3] bg-white px-4 text-sm font-semibold text-foreground shadow-xs transition hover:bg-[#f5f5f3] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 active:translate-y-px">
    <span aria-hidden="true" className="grid h-5 w-5 place-items-center rounded-full border border-[#cfcfcb] text-[12px] font-bold">G</span>
    {children}
  </a>
}

function Divider() {
  return <div className="my-6 flex items-center gap-4 text-[11px] font-semibold uppercase tracking-[0.16em] text-[#858582]"><span className="h-px flex-1 bg-border" /><span>or</span><span className="h-px flex-1 bg-border" /></div>
}

function MutationError({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof ApiProblem ? error.message : 'Something went wrong. Please try again.'
  return <p role="alert" className="rounded-[11px] border border-[#cfcfcb] bg-[#f4f4f2] px-4 py-3 text-sm leading-5">{message}</p>
}

function safeReturnPath(state: unknown) {
  const candidate = (state as { from?: unknown } | null)?.from
  return safeInternalPath(candidate) ?? '/applications'
}

function SuccessMessage({ title, children }: { title: string; children: ReactNode }) {
  return <div role="status" className="rounded-[14px] border bg-[#f5f5f3] p-5">
    <div className="flex items-center gap-3"><span className="grid h-8 w-8 place-items-center rounded-full bg-[#20211f] text-white"><Check size={16} aria-hidden="true" /></span><p className="font-semibold">{title}</p></div>
    <div className="mt-3 text-sm leading-6 text-muted-foreground">{children}</div>
  </div>
}

function VerificationResendForm({ initialEmail = '' }: { initialEmail?: string }) {
  const emailId = useId()
  const [email, setEmail] = useState(initialEmail)
  const mutation = useMutation({ mutationFn: () => resendVerification(email) })

  if (mutation.isSuccess) {
    return <SuccessMessage title="Check your inbox"><p>{mutation.data.message}</p></SuccessMessage>
  }

  return <form className="space-y-4 rounded-[14px] border bg-[#fafaf8] p-5" onSubmit={(event) => { event.preventDefault(); if (!mutation.isPending) mutation.mutate() }}>
    <div><p className="font-semibold">Need a new verification link?</p><p className="mt-1 text-sm leading-6 text-muted-foreground">Enter your email and we’ll send the next step if the account is eligible.</p></div>
    <div><Label htmlFor={emailId}>Email</Label><Input id={emailId} type="email" autoComplete="email" value={email} onChange={(event) => { setEmail(event.target.value); if (!mutation.isPending) mutation.reset() }} className="min-h-12" disabled={mutation.isPending} required /></div>
    <MutationError error={mutation.error} />
    <Button type="submit" disabled={mutation.isPending} className="min-h-12 w-full">{mutation.isPending ? 'Sending…' : 'Resend verification email'}</Button>
  </form>
}

export function SignInPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(false)
  const oauthFailed = searchParams.get('oauthError') === 'oauth_failed'
  const returnTo = oauthFailed ? readOAuthReturnPath() ?? '/applications' : safeReturnPath(location.state)
  const mutation = useMutation({
    mutationFn: () => login(email, password, rememberMe),
    onSuccess: () => {
      clearOAuthReturnPath()
      navigate(returnTo, { replace: true })
    },
  })
  if (user) return <Navigate to="/applications" replace />

  return <AuthLayout eyebrow="Welcome back" title="Sign in to ApplyFlow" description="Return to your applications, follow-ups, and next opportunities.">
    {oauthFailed ? <div role="alert" className="rounded-[11px] border bg-[#f4f4f2] p-4 text-sm">
      <p>Google sign-in could not be completed. No account details were changed.</p>
      <div className="mt-4"><GoogleButton returnTo={returnTo}>Try Google again</GoogleButton></div>
      <p className="mt-3 text-muted-foreground">Or sign in with your email and password below.</p>
    </div> : <GoogleButton returnTo={returnTo} />}
    <Divider />
    <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}>
      <div><Label htmlFor="email">Email</Label><Input id="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} className="min-h-12" required /></div>
      <div><div className="flex items-baseline justify-between gap-4"><Label htmlFor="password">Password</Label><Link to="/forgot-password" className="text-sm font-medium underline-offset-4 hover:underline">Forgot password?</Link></div><PasswordInput id="password" value={password} onChange={setPassword} autoComplete="current-password" /></div>
      <label className="flex w-fit items-center gap-2.5 text-sm text-[#4f4f4d]"><input type="checkbox" checked={rememberMe} onChange={(event) => setRememberMe(event.target.checked)} className="h-4 w-4 accent-black" />Remember me</label>
      <MutationError error={mutation.error} />
      <Button type="submit" disabled={mutation.isPending} className="min-h-12 w-full">{mutation.isPending ? 'Signing in…' : 'Sign in'}</Button>
    </form>
    <p className="mt-4 text-center text-sm text-muted-foreground">Still waiting for verification? <Link to="/verify-email" className="font-semibold text-foreground underline-offset-4 hover:underline">Resend verification email</Link></p>
    <p className="mt-7 text-center text-sm text-muted-foreground">New to ApplyFlow? <Link to="/sign-up" className="font-semibold text-foreground underline-offset-4 hover:underline">Create an account</Link></p>
  </AuthLayout>
}

export function SignUpPage() {
  const { user } = useAuth()
  const [values, setValues] = useState({ fullName: '', email: '', password: '', passwordConfirmation: '' })
  const mutation = useMutation({ mutationFn: () => register(values) })
  const mismatch = values.passwordConfirmation.length > 0 && values.password !== values.passwordConfirmation
  const set = (key: keyof typeof values) => (value: string) => setValues((current) => ({ ...current, [key]: value }))

  if (user) return <Navigate to="/applications" replace />
  return <AuthLayout eyebrow="Your account" title="Create your space" description="Build a secure, private home for every application and decision in your search.">
    {mutation.isSuccess ? <div className="space-y-5"><SuccessMessage title="Check your inbox"><p>We sent the next step to <strong className="text-foreground">{values.email}</strong>. Verify your email before signing in.</p></SuccessMessage><VerificationResendForm initialEmail={values.email} /><Link to="/sign-in" className="inline-block font-semibold text-foreground underline-offset-4 hover:underline">Back to sign in</Link></div> : <>
      <GoogleButton /><Divider />
      <form className="space-y-4" onSubmit={(event) => { event.preventDefault(); if (!mismatch) mutation.mutate() }}>
        <div><Label htmlFor="fullName">Full name</Label><Input id="fullName" autoComplete="name" value={values.fullName} onChange={(event) => set('fullName')(event.target.value)} className="min-h-12" required /></div>
        <div><Label htmlFor="email">Email</Label><Input id="email" type="email" autoComplete="email" value={values.email} onChange={(event) => set('email')(event.target.value)} className="min-h-12" required /></div>
        <div><Label htmlFor="password">Password</Label><PasswordInput id="password" value={values.password} onChange={set('password')} autoComplete="new-password" /><p className="mt-1.5 text-xs text-muted-foreground">Use at least 12 characters.</p></div>
        <div><Label htmlFor="passwordConfirmation">Confirm password</Label><PasswordInput id="passwordConfirmation" value={values.passwordConfirmation} onChange={set('passwordConfirmation')} autoComplete="new-password" /><FieldError message={mismatch ? 'Passwords do not match.' : undefined} /></div>
        <MutationError error={mutation.error} />
        <Button type="submit" disabled={mutation.isPending || mismatch} className="min-h-12 w-full">{mutation.isPending ? 'Creating account…' : 'Create account'}</Button>
      </form>
      <p className="mt-7 text-center text-sm text-muted-foreground">Already have an account? <Link to="/sign-in" className="font-semibold text-foreground underline-offset-4 hover:underline">Sign in</Link></p>
    </>}
  </AuthLayout>
}

export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const mutation = useMutation({ mutationFn: () => forgotPassword(email) })
  return <AuthLayout eyebrow="Account recovery" title="Reset your password" description="Enter your email. If an eligible account exists, we’ll send a secure, short-lived link.">
    {mutation.isSuccess ? <SuccessMessage title="Check your inbox"><p>If an eligible account exists for <strong className="text-foreground">{email}</strong>, a recovery link is on its way.</p></SuccessMessage> : <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); mutation.mutate() }}>
      <div><Label htmlFor="email">Email</Label><Input id="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} className="min-h-12" required /></div>
      <MutationError error={mutation.error} />
      <Button type="submit" disabled={mutation.isPending} className="min-h-12 w-full">{mutation.isPending ? 'Sending…' : 'Send reset link'}</Button>
    </form>}
    <Link to="/sign-in" className="mt-7 inline-block text-sm font-semibold underline-offset-4 hover:underline">Back to sign in</Link>
  </AuthLayout>
}

export function ResetPasswordPage() {
  const [searchParams] = useSearchParams()
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const token = searchParams.get('token') ?? ''
  const mismatch = confirmation.length > 0 && password !== confirmation
  const mutation = useMutation({ mutationFn: () => resetPassword({ token, password, passwordConfirmation: confirmation }) })
  return <AuthLayout eyebrow="Secure link" title="Choose a new password" description="This link can be used once. Your other active sessions will be closed after the change.">
    {!token ? <MutationError error={new ApiProblem({ detail: 'This password link is missing its token.' }, 400)} /> : mutation.isSuccess ? <SuccessMessage title="Password updated"><p>Your new password is ready.</p><Link to="/sign-in" className="mt-4 inline-block font-semibold text-foreground underline-offset-4 hover:underline">Continue to sign in</Link></SuccessMessage> : <form className="space-y-5" onSubmit={(event) => { event.preventDefault(); if (!mismatch) mutation.mutate() }}>
      <div><Label htmlFor="password">New password</Label><PasswordInput id="password" value={password} onChange={setPassword} autoComplete="new-password" /></div>
      <div><Label htmlFor="confirmation">Confirm new password</Label><PasswordInput id="confirmation" value={confirmation} onChange={setConfirmation} autoComplete="new-password" /><FieldError message={mismatch ? 'Passwords do not match.' : undefined} /></div>
      <MutationError error={mutation.error} />
      <Button type="submit" disabled={mutation.isPending || mismatch} className="min-h-12 w-full">{mutation.isPending ? 'Updating…' : 'Update password'}</Button>
    </form>}
  </AuthLayout>
}

export function VerifyEmailPage() {
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token') ?? ''
  const started = useRef(false)
  const mutation = useMutation({ mutationFn: () => verifyEmail(token) })
  useEffect(() => {
    if (token && !started.current) {
      started.current = true
      mutation.mutate()
    }
  }, [token, mutation])
  const linkError = token ? mutation.error : new ApiProblem({ detail: 'This verification link is missing its token.' }, 400)
  const needsRecovery = !token || mutation.isError
  return <AuthLayout eyebrow="Email verification" title="Confirming your email" description="We’re validating this one-time link and preparing your account.">
    {needsRecovery ? <div className="space-y-5"><MutationError error={linkError} /><VerificationResendForm /><Link to="/sign-in" className="inline-block text-sm font-semibold underline-offset-4 hover:underline">Back to sign in</Link></div> : mutation.isPending || mutation.isIdle ? <div role="status" className="rounded-[14px] border p-5 text-sm text-muted-foreground">Verifying secure link…</div> : <SuccessMessage title="Email verified"><p>Your account is ready.</p><Link to="/sign-in" className="mt-4 inline-block font-semibold text-foreground underline-offset-4 hover:underline">Continue to sign in</Link></SuccessMessage>}
  </AuthLayout>
}

export function OAuthCallbackPage() {
  const session = useAuth()
  const navigate = useNavigate()
  const [failed, setFailed] = useState(false)
  const finalized = useRef(false)
  const returnTo = readOAuthReturnPath() ?? '/applications'
  useEffect(() => {
    if (session.status === 'loading' || session.status === 'unavailable' || finalized.current) return
    finalized.current = true
    if (session.status === 'authenticated') {
      navigate(consumeOAuthReturnPath(), { replace: true })
    } else {
      setFailed(true)
    }
  }, [navigate, session.status])
  return <AuthLayout eyebrow="Google sign-in" title="Completing sign in" description="ApplyFlow is establishing your secure session.">
    {session.status === 'unavailable' ? <div role="alert" className="space-y-4 rounded-[14px] border bg-[#f4f4f2] p-5 text-sm">
      <div><p className="font-semibold">We couldn’t verify your session</p><p className="mt-1 text-muted-foreground">ApplyFlow could not reach the session service. Try the session check again.</p></div>
      <Button disabled={session.isRetrying} onClick={() => void session.retrySession()}>{session.isRetrying ? 'Checking session…' : 'Retry session check'}</Button>
      <Link to="/sign-in" state={{ from: returnTo }} className="inline-block font-semibold underline-offset-4 hover:underline">Back to sign in</Link>
    </div> : failed ? <div role="alert" className="space-y-4 rounded-[14px] border bg-[#f4f4f2] p-5 text-sm">
      <p>Google sign-in could not be completed. Please try again or return to sign in.</p>
      <GoogleButton returnTo={returnTo}>Try Google again</GoogleButton>
      <Link to="/sign-in" state={{ from: returnTo }} className="inline-block font-semibold underline-offset-4 hover:underline">Back to sign in</Link>
    </div> : <div role="status" className="rounded-[14px] border p-5 text-sm text-muted-foreground">Finalizing your account…</div>}
  </AuthLayout>
}

export function SecuritySettingsPage() {
  const { user } = useAuth()
  const [values, setValues] = useState({ currentPassword: '', password: '', passwordConfirmation: '' })
  const passwordMutation = useMutation({ mutationFn: () => changePassword(values), onSuccess: () => setValues({ currentPassword: '', password: '', passwordConfirmation: '' }) })
  const setupMutation = useMutation({ mutationFn: requestPasswordSetup })
  const hasPassword = user?.authenticationMethods.includes('PASSWORD') ?? false
  const mismatch = values.passwordConfirmation.length > 0 && values.password !== values.passwordConfirmation
  const field = (key: keyof typeof values) => (value: string) => setValues((current) => ({ ...current, [key]: value }))

  return <div className="mx-auto max-w-[680px] pb-14">
    <p className="text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground">Account</p>
    <h1 className="mt-2 text-4xl font-semibold tracking-[-0.045em]">Security</h1>
    <p className="mt-3 text-sm leading-6 text-muted-foreground">Manage the password attached to {user?.email}.</p>
    <div className="mt-9 rounded-[15px] border p-6 sm:p-8">
      <div className="flex items-center gap-3"><LockKeyhole size={20} aria-hidden="true" /><h2 className="text-lg font-semibold">Password</h2></div>
      {!hasPassword ? <div className="mt-5"><p className="text-sm leading-6 text-muted-foreground">This account currently signs in with Google. Request a secure email link to add password sign-in.</p><MutationError error={setupMutation.error} />{setupMutation.isSuccess ? <SuccessMessage title="Check your inbox"><p>We sent a password setup link.</p></SuccessMessage> : <Button className="mt-5" onClick={() => setupMutation.mutate()} disabled={setupMutation.isPending}><Mail size={17} aria-hidden="true" />Send password setup link</Button>}</div> : <form className="mt-6 space-y-5" onSubmit={(event: FormEvent) => { event.preventDefault(); if (!mismatch) passwordMutation.mutate() }}>
        <div><Label htmlFor="currentPassword">Current password</Label><PasswordInput id="currentPassword" value={values.currentPassword} onChange={field('currentPassword')} autoComplete="current-password" /></div>
        <div><Label htmlFor="newPassword">New password</Label><PasswordInput id="newPassword" value={values.password} onChange={field('password')} autoComplete="new-password" /></div>
        <div><Label htmlFor="confirmPassword">Confirm new password</Label><PasswordInput id="confirmPassword" value={values.passwordConfirmation} onChange={field('passwordConfirmation')} autoComplete="new-password" /><FieldError message={mismatch ? 'Passwords do not match.' : undefined} /></div>
        <MutationError error={passwordMutation.error} />
        {passwordMutation.isSuccess && <SuccessMessage title="Password changed"><p>Your other sessions have been closed.</p></SuccessMessage>}
        <Button type="submit" disabled={passwordMutation.isPending || mismatch}>{passwordMutation.isPending ? 'Saving…' : 'Change password'}</Button>
      </form>}
    </div>
  </div>
}
