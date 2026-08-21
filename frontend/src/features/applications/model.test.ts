import { describe, expect, it } from 'vitest'
import { APPLICATION_STATUSES } from '@/types/api'
import { trackerGroupFor } from './model'

describe('trackerGroupFor', () => {
  it('assigns every application status to exactly one approved tracker group', () => {
    const result = Object.fromEntries(APPLICATION_STATUSES.map((status) => [status, trackerGroupFor(status)]))
    expect(result).toEqual({
      BOOKMARKED: 'bookmarked', APPLIED: 'applied', RESPONSE_RECEIVED: 'applied',
      HR_INTERVIEW: 'interview', TECHNICAL_INTERVIEW: 'interview', FINAL_INTERVIEW: 'interview',
      OFFER: 'offer', REJECTED: 'closed', WITHDRAWN: 'closed',
    })
  })
})
