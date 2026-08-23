export const loadApplicationPages = () => import('@/features/applications/pages')
export const loadAuthPages = () => import('@/features/auth/pages')
export const loadAnalyticsPage = () => import('@/features/analytics/AnalyticsPage')

let newApplicationPagePromise: Promise<typeof import('@/features/applications/NewApplicationPage')> | undefined

export function loadNewApplicationPage() {
  newApplicationPagePromise ??= import('@/features/applications/NewApplicationPage')
  return newApplicationPagePromise
}
