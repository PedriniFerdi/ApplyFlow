import type { ApplicationStatus, JobApplicationSummary } from '@/types/api'

export const STATUS_LABELS: Record<ApplicationStatus, string> = {
  BOOKMARKED: 'Bookmarked',
  APPLIED: 'Applied',
  RESPONSE_RECEIVED: 'Response received',
  HR_INTERVIEW: 'HR interview',
  TECHNICAL_INTERVIEW: 'Technical interview',
  FINAL_INTERVIEW: 'Final interview',
  OFFER: 'Offer',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
}

export type TrackerGroup = 'bookmarked' | 'applied' | 'interview' | 'offer' | 'closed'

export const ACTIVE_TRACKER_GROUPS: Array<{ id: Exclude<TrackerGroup, 'closed'>; label: string }> = [
  { id: 'bookmarked', label: 'Wishlist' },
  { id: 'applied', label: 'Applied' },
  { id: 'interview', label: 'Interview' },
  { id: 'offer', label: 'Offer' },
]

export const TRACKER_GROUPS: Array<{ id: TrackerGroup; label: string }> = [
  ...ACTIVE_TRACKER_GROUPS,
  { id: 'closed', label: 'Closed' },
]

export function trackerGroupFor(status: ApplicationStatus): TrackerGroup {
  if (status === 'BOOKMARKED') return 'bookmarked'
  if (status === 'APPLIED' || status === 'RESPONSE_RECEIVED') return 'applied'
  if (status === 'HR_INTERVIEW' || status === 'TECHNICAL_INTERVIEW' || status === 'FINAL_INTERVIEW') return 'interview'
  if (status === 'OFFER') return 'offer'
  return 'closed'
}

export function groupApplications(items: JobApplicationSummary[]): Record<TrackerGroup, JobApplicationSummary[]> {
  const groups: Record<TrackerGroup, JobApplicationSummary[]> = {
    bookmarked: [], applied: [], interview: [], offer: [], closed: [],
  }
  for (const item of items) groups[trackerGroupFor(item.status)].push(item)
  return groups
}

export function formatDate(value: string | null): string {
  if (!value) return 'Not provided'
  const [year, month, day] = value.split('-').map(Number)
  return new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
    .format(new Date(year, month - 1, day))
}

export function formatInstant(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
