import { afterEach, describe, expect, it, vi } from 'vitest'
import type { JobApplicationDetail } from '@/types/api'
import { applicationFormDefaults, applicationFormSchema, localDateValue, toApplicationRequest } from './form-model'

describe('application form model', () => {
  afterEach(() => vi.useRealTimers())

  it('requires an applied date outside bookmarked status', () => {
    const values = { ...applicationFormDefaults(), companyId: '4', positionTitle: 'Engineer', sourceId: '2', status: 'APPLIED' as const, appliedDate: '' }
    const result = applicationFormSchema.safeParse(values)
    expect(result.success).toBe(false)
    if (!result.success) expect(result.error.issues).toContainEqual(expect.objectContaining({ path: ['appliedDate'] }))
  })

  it('defaults a new application to the local current date without UTC conversion', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 7, 14, 23, 30))
    const dateWithDifferentUtcDay = {
      getFullYear: () => 2026,
      getMonth: () => 7,
      getDate: () => 14,
      toISOString: () => '2026-08-15T02:30:00.000Z',
    } as unknown as Date

    expect(applicationFormDefaults().appliedDate).toBe('2026-08-14')
    expect(localDateValue(dateWithDifferentUtcDay)).toBe('2026-08-14')
  })

  it('preserves the applied date when editing an existing application', () => {
    const detail = {
      company: { id: 4 },
      source: { id: 2 },
      appliedDate: '2024-05-20',
      technologies: [],
    } as unknown as JobApplicationDetail

    expect(applicationFormDefaults(detail).appliedDate).toBe('2024-05-20')
    expect(applicationFormDefaults({ ...detail, appliedDate: null }).appliedDate).toBe('')
  })

  it('requires salary metadata and rejects max below min', () => {
    const values = { ...applicationFormDefaults(), companyId: '4', positionTitle: 'Engineer', sourceId: '2', salaryMin: '90000', salaryMax: '80000' }
    const result = applicationFormSchema.safeParse(values)
    expect(result.success).toBe(false)
    if (!result.success) expect(result.error.issues.map(({ path }) => path[0])).toEqual(expect.arrayContaining(['salaryMax', 'currency', 'salaryPeriod']))
  })

  it('sends exactly one company representation and canonical optional values', () => {
    const values = { ...applicationFormDefaults(), companyMode: 'new' as const, newCompanyName: ' Acme ', positionTitle: ' Engineer ', sourceId: '2', technologyIds: ['3', '3'] }
    const request = toApplicationRequest(values)
    expect(request.companyId).toBeUndefined()
    expect(request.newCompany).toMatchObject({ name: 'Acme', website: null })
    expect(request.positionTitle).toBe('Engineer')
    expect(request.technologyIds).toEqual([3])
  })

  it('keeps 19,2 decimal salary values as strings without JavaScript number rounding', () => {
    const values = { ...applicationFormDefaults(), companyId: '4', positionTitle: 'Engineer', sourceId: '2', salaryMin: '9007199254740991.01', salaryMax: '99999999999999999.99', currency: 'USD', salaryPeriod: 'YEARLY' as const }
    expect(applicationFormSchema.safeParse(values).success).toBe(true)
    const request = toApplicationRequest(values)
    expect(request.salaryMin).toBe('9007199254740991.01')
    expect(request.salaryMax).toBe('99999999999999999.99')
  })

  it('rejects salary strings beyond the PostgreSQL NUMERIC(19,2) contract', () => {
    const values = { ...applicationFormDefaults(), companyId: '4', positionTitle: 'Engineer', sourceId: '2', salaryMin: '100000000000000000.00', currency: 'USD', salaryPeriod: 'YEARLY' as const }
    expect(applicationFormSchema.safeParse(values).success).toBe(false)
  })

  it('counts astral characters as one persisted character at request boundaries', () => {
    const base = { ...applicationFormDefaults(), companyId: '4', sourceId: '2' }
    expect(applicationFormSchema.safeParse({ ...base, positionTitle: '🚀'.repeat(180), notes: '🚀'.repeat(5000) }).success).toBe(true)
    expect(applicationFormSchema.safeParse({ ...base, positionTitle: '🚀'.repeat(181) }).success).toBe(false)
    expect(applicationFormSchema.safeParse({ ...base, positionTitle: 'Engineer', notes: '🚀'.repeat(5001) }).success).toBe(false)
  })

  it('bounds technology selections and salary request strings', () => {
    const base = { ...applicationFormDefaults(), companyId: '4', positionTitle: 'Engineer', sourceId: '2' }
    expect(applicationFormSchema.safeParse({ ...base, technologyIds: Array.from({ length: 50 }, (_, index) => String(index)) }).success).toBe(true)
    expect(applicationFormSchema.safeParse({ ...base, technologyIds: Array.from({ length: 51 }, (_, index) => String(index)) }).success).toBe(false)
    expect(applicationFormSchema.safeParse({ ...base, salaryMin: '1'.repeat(21) }).success).toBe(false)
  })

  it('uses distinct Unicode code-point limits for company and job URLs', () => {
    const base = { ...applicationFormDefaults(), companyId: '4', positionTitle: 'Engineer', sourceId: '2' }
    expect(applicationFormSchema.safeParse({ ...base, newCompanyWebsite: 'https://' + '🚀'.repeat(492), jobUrl: 'https://' + '🚀'.repeat(992) }).success).toBe(true)
    expect(applicationFormSchema.safeParse({ ...base, newCompanyWebsite: 'https://' + '🚀'.repeat(493), jobUrl: 'https://' + '🚀'.repeat(993) }).success).toBe(false)
  })
})
