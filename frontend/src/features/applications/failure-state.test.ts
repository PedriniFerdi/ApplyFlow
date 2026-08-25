import { describe, expect, it } from 'vitest'
import { ApiProblem } from '@/lib/api-client'
import { describeMutationFailure } from './failure-state'

describe('describeMutationFailure', () => {
  it('distinguishes validation, conflict, and retryable failures', () => {
    expect(describeMutationFailure(new ApiProblem({ status: 422, detail: 'Position is required', errors: { positionTitle: 'Required' } }, 422), 'Application save')).toMatchObject({ kind: 'validation', title: 'Application save needs attention' })
    expect(describeMutationFailure(new ApiProblem({ status: 409, detail: 'Application changed' }, 409), 'Application save')).toMatchObject({ kind: 'conflict', title: 'Application save has a conflict' })
    expect(describeMutationFailure(new ApiProblem({ status: 503, detail: 'Service unavailable' }, 503), 'Application save')).toMatchObject({ kind: 'retryable', title: 'Application save could not be completed' })
  })

  it('keeps safe recovery guidance for network failures', () => {
    const failure = describeMutationFailure(new ApiProblem({ status: 0, detail: 'Unable to reach the API' }, 0), 'Status change')
    expect(failure?.message).toContain('Your input is still here')
  })
})
