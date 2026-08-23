import { lazy, Suspense, type ReactNode } from 'react'
import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { AppShell } from '@/components/AppShell'
import { Button } from '@/components/ui'
import { useAuth } from '@/features/auth/auth-context'
import { loadAnalyticsPage, loadApplicationPages, loadAuthPages, loadNewApplicationPage } from '@/route-loaders'

const ApplicationsPage = lazy(() => loadApplicationPages().then(({ ApplicationsPage }) => ({ default: ApplicationsPage })))
const ApplicationDetailPage = lazy(() => loadApplicationPages().then(({ ApplicationDetailPage }) => ({ default: ApplicationDetailPage })))
const EditApplicationPage = lazy(() => loadApplicationPages().then(({ EditApplicationPage }) => ({ default: EditApplicationPage })))
const TrackerPage = lazy(() => loadApplicationPages().then(({ TrackerPage }) => ({ default: TrackerPage })))
const NotFoundPage = lazy(() => loadApplicationPages().then(({ NotFoundPage }) => ({ default: NotFoundPage })))
const NewApplicationPage = lazy(loadNewApplicationPage)
const AnalyticsPage = lazy(loadAnalyticsPage)
const SignInPage = lazy(() => loadAuthPages().then(({ SignInPage }) => ({ default: SignInPage })))
const SignUpPage = lazy(() => loadAuthPages().then(({ SignUpPage }) => ({ default: SignUpPage })))
const ForgotPasswordPage = lazy(() => loadAuthPages().then(({ ForgotPasswordPage }) => ({ default: ForgotPasswordPage })))
const ResetPasswordPage = lazy(() => loadAuthPages().then(({ ResetPasswordPage }) => ({ default: ResetPasswordPage })))
const VerifyEmailPage = lazy(() => loadAuthPages().then(({ VerifyEmailPage }) => ({ default: VerifyEmailPage })))
const OAuthCallbackPage = lazy(() => loadAuthPages().then(({ OAuthCallbackPage }) => ({ default: OAuthCallbackPage })))
const SecuritySettingsPage = lazy(() => loadAuthPages().then(({ SecuritySettingsPage }) => ({ default: SecuritySettingsPage })))

function RouteFallback() {
  return <div role="status" className="grid min-h-64 place-items-center"><div className="w-56 space-y-3"><span className="sr-only">Loading page</span><span className="block h-3 animate-pulse rounded-full bg-[#d7d7d4]" /><span className="block h-3 w-2/3 animate-pulse rounded-full bg-[#dfdfdc]" /></div></div>
}

function LazyRoute({ children }: { children: ReactNode }) {
  return <Suspense fallback={<RouteFallback />}>{children}</Suspense>
}

function RequireAuthentication() {
  const session = useAuth()
  const location = useLocation()
  if (session.status === 'loading') return <main className="grid min-h-[100dvh] place-items-center bg-[#f3f3f2]"><div role="status" className="w-56 space-y-3"><span className="sr-only">Loading your session</span><span className="block h-3 animate-pulse rounded-full bg-[#d7d7d4]" /><span className="block h-3 w-2/3 animate-pulse rounded-full bg-[#dfdfdc]" /></div></main>
  if (session.status === 'unavailable') return <main className="grid min-h-[100dvh] place-items-center bg-[#f3f3f2] p-5"><div role="alert" className="w-full max-w-md rounded-[15px] border bg-white p-6 shadow-sm"><h1 className="text-xl font-semibold">We couldn’t verify your session</h1><p className="mt-2 text-sm leading-6 text-muted-foreground">ApplyFlow could not reach the session service. Your account data has not been shown.</p><Button className="mt-5" disabled={session.isRetrying} onClick={() => void session.retrySession()}>{session.isRetrying ? 'Checking session…' : 'Retry session check'}</Button></div></main>
  if (session.status === 'anonymous') return <Navigate to="/sign-in" state={{ from: `${location.pathname}${location.search}` }} replace />
  return <Outlet />
}

function App() {
  return <Routes>
    <Route path="sign-in" element={<LazyRoute><SignInPage /></LazyRoute>} />
    <Route path="sign-up" element={<LazyRoute><SignUpPage /></LazyRoute>} />
    <Route path="forgot-password" element={<LazyRoute><ForgotPasswordPage /></LazyRoute>} />
    <Route path="reset-password" element={<LazyRoute><ResetPasswordPage /></LazyRoute>} />
    <Route path="verify-email" element={<LazyRoute><VerifyEmailPage /></LazyRoute>} />
    <Route path="auth/callback" element={<LazyRoute><OAuthCallbackPage /></LazyRoute>} />
    <Route element={<RequireAuthentication />}>
      <Route element={<AppShell />}>
        <Route index element={<Navigate to="/applications" replace />} />
        <Route path="applications" element={<LazyRoute><ApplicationsPage /></LazyRoute>} />
        <Route path="applications/new" element={<LazyRoute><NewApplicationPage /></LazyRoute>} />
        <Route path="applications/:id" element={<LazyRoute><ApplicationDetailPage /></LazyRoute>} />
        <Route path="applications/:id/edit" element={<LazyRoute><EditApplicationPage /></LazyRoute>} />
        <Route path="tracker" element={<LazyRoute><TrackerPage /></LazyRoute>} />
        <Route path="analytics" element={<LazyRoute><AnalyticsPage /></LazyRoute>} />
        <Route path="settings/security" element={<LazyRoute><SecuritySettingsPage /></LazyRoute>} />
        <Route path="not-found" element={<LazyRoute><NotFoundPage /></LazyRoute>} />
        <Route path="*" element={<LazyRoute><NotFoundPage /></LazyRoute>} />
      </Route>
    </Route>
  </Routes>
}

export default App
