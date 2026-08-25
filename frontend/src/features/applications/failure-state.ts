import { ApiProblem } from '@/lib/api-client'

export type MutationFailureKind = 'validation' | 'conflict' | 'retryable' | 'unavailable'

export interface MutationFailure {
  kind: MutationFailureKind
  title: string
  message: string
}

export function describeMutationFailure(error: unknown, subject: string): MutationFailure | null {
  if (!error) return null
  if (!(error instanceof ApiProblem)) {
    return { kind: 'unavailable', title: `${subject} was not completed`, message: 'Your input is still here. Review it and try again.' }
  }
  if (Object.keys(error.errors).length > 0 || error.status === 400 || error.status === 422) {
    return { kind: 'validation', title: `${subject} needs attention`, message: `${error.message} Your input is still here.` }
  }
  if (error.status === 409) {
    return { kind: 'conflict', title: `${subject} has a conflict`, message: `${error.message} Refresh the affected data if needed, then try again.` }
  }
  if (error.status === 0 || error.status === 408 || error.status === 429 || error.status >= 500) {
    return { kind: 'retryable', title: `${subject} could not be completed`, message: `${error.message} Your input is still here; try again when the service is available.` }
  }
  return { kind: 'unavailable', title: `${subject} was not completed`, message: error.message }
}
