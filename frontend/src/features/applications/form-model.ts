import { z } from 'zod'
import { APPLICATION_STATUSES, type ApplicationStatus, type ApplicationWriteRequest, type CompanyType, type CreateApplicationRequest, type JobApplicationDetail, type SalaryPeriod, type WorkMode } from '@/types/api'

const codePointLimited = (maximum: number) => z.string().refine(
  (value) => Array.from(value).length <= maximum,
  `Must contain at most ${maximum} characters`,
)
const optionalUrl = (maximum: number) => codePointLimited(maximum).refine((value) => !value || /^https?:\/\//i.test(value), 'Use an HTTP or HTTPS URL')

export const applicationFormSchema = z.object({
  companyMode: z.enum(['existing', 'new']), companyId: z.string(), newCompanyName: codePointLimited(160),
  newCompanyWebsite: optionalUrl(500), newCompanyType: z.enum(['STARTUP', 'SCALEUP', 'CORPORATE', 'CONSULTING', 'OTHER']), newCompanyIndustry: codePointLimited(120),
  positionTitle: codePointLimited(180).pipe(z.string().trim().min(1, 'Position title is required')), jobUrl: optionalUrl(1000),
  appliedDate: z.string(), status: z.enum(APPLICATION_STATUSES), sourceId: z.string().min(1, 'Source is required'),
  workMode: z.enum(['REMOTE', 'HYBRID', 'ONSITE']), location: codePointLimited(160),
  salaryMin: z.string().max(20), salaryMax: z.string().max(20), currency: z.string().max(3), salaryPeriod: z.union([z.enum(['YEARLY', 'MONTHLY', 'HOURLY']), z.literal('')]),
  notes: codePointLimited(5000), technologyIds: z.array(z.string()).max(50),
}).superRefine((values, ctx) => {
  if (values.companyMode === 'existing' && !values.companyId) ctx.addIssue({ code: 'custom', path: ['companyId'], message: 'Select a company' })
  if (values.companyMode === 'new' && !values.newCompanyName.trim()) ctx.addIssue({ code: 'custom', path: ['newCompanyName'], message: 'Company name is required' })
  if (values.status !== 'BOOKMARKED' && !values.appliedDate) ctx.addIssue({ code: 'custom', path: ['appliedDate'], message: 'Applied date is required for this status' })
  if (values.appliedDate && values.appliedDate > localDateValue()) ctx.addIssue({ code: 'custom', path: ['appliedDate'], message: 'Applied date cannot be in the future' })
  const min = parseSalary(values.salaryMin)
  const max = parseSalary(values.salaryMax)
  if (values.salaryMin !== '' && min === null) ctx.addIssue({ code: 'custom', path: ['salaryMin'], message: 'Use a non-negative decimal with up to 17 integer and 2 fractional digits' })
  if (values.salaryMax !== '' && max === null) ctx.addIssue({ code: 'custom', path: ['salaryMax'], message: 'Use a non-negative decimal with up to 17 integer and 2 fractional digits' })
  if (min !== null && max !== null && compareDecimals(max, min) < 0) ctx.addIssue({ code: 'custom', path: ['salaryMax'], message: 'Maximum must be greater than or equal to minimum' })
  const hasSalary = min !== null || max !== null
  if (hasSalary && !/^[A-Za-z]{3}$/.test(values.currency)) ctx.addIssue({ code: 'custom', path: ['currency'], message: 'Use a three-letter currency code' })
  if (hasSalary && !values.salaryPeriod) ctx.addIssue({ code: 'custom', path: ['salaryPeriod'], message: 'Salary period is required' })
  if (!hasSalary && values.currency) ctx.addIssue({ code: 'custom', path: ['currency'], message: 'Remove currency or add a salary amount' })
  if (!hasSalary && values.salaryPeriod) ctx.addIssue({ code: 'custom', path: ['salaryPeriod'], message: 'Remove period or add a salary amount' })
})

export type ApplicationFormValues = z.infer<typeof applicationFormSchema>

export function localDateValue(date = new Date()) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function applicationFormDefaults(detail?: JobApplicationDetail): ApplicationFormValues {
  return {
    companyMode: 'existing', companyId: detail ? String(detail.company.id) : '', newCompanyName: '', newCompanyWebsite: '', newCompanyType: 'OTHER', newCompanyIndustry: '',
    positionTitle: detail?.positionTitle ?? '', jobUrl: detail?.jobUrl ?? '', appliedDate: detail ? (detail.appliedDate ?? '') : localDateValue(), status: detail?.status ?? 'BOOKMARKED',
    sourceId: detail ? String(detail.source.id) : '', workMode: detail?.workMode ?? 'REMOTE', location: detail?.location ?? '',
    salaryMin: detail?.salaryMin == null ? '' : String(detail.salaryMin), salaryMax: detail?.salaryMax == null ? '' : String(detail.salaryMax),
    currency: detail?.currency ?? '', salaryPeriod: detail?.salaryPeriod ?? '', notes: detail?.notes ?? '', technologyIds: detail?.technologies.map(({ id }) => String(id)) ?? [],
  }
}

function optional(value: string) { const normalized = value.trim(); return normalized || null }

function parseSalary(value: string) {
  if (value === '') return null
  return /^(?:0|[1-9]\d{0,16})(?:\.\d{1,2})?$/.test(value) ? value : null
}

function compareDecimals(left: string, right: string) {
  const [leftWhole, leftFraction = ''] = left.split('.')
  const [rightWhole, rightFraction = ''] = right.split('.')
  if (leftWhole.length !== rightWhole.length) return leftWhole.length - rightWhole.length
  if (leftWhole !== rightWhole) return leftWhole < rightWhole ? -1 : 1
  return leftFraction.padEnd(2, '0').localeCompare(rightFraction.padEnd(2, '0'))
}

export function toApplicationRequest(values: ApplicationFormValues): CreateApplicationRequest {
  const base: ApplicationWriteRequest = {
    ...(values.companyMode === 'existing' ? { companyId: Number(values.companyId) } : { newCompany: { name: values.newCompanyName.trim(), website: optional(values.newCompanyWebsite), companyType: values.newCompanyType as CompanyType, industry: optional(values.newCompanyIndustry) } }),
    positionTitle: values.positionTitle.trim(), jobUrl: optional(values.jobUrl), appliedDate: values.appliedDate || null, sourceId: Number(values.sourceId), workMode: values.workMode as WorkMode,
    location: optional(values.location), salaryMin: values.salaryMin || null, salaryMax: values.salaryMax || null,
    currency: values.currency ? values.currency.toUpperCase() : null, salaryPeriod: (values.salaryPeriod || null) as SalaryPeriod | null, notes: optional(values.notes),
    technologyIds: [...new Set(values.technologyIds.map(Number))],
  }
  return { ...base, status: values.status as ApplicationStatus }
}
