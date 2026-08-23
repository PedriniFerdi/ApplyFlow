export const APPLICATION_STATUSES = [
  'BOOKMARKED',
  'APPLIED',
  'RESPONSE_RECEIVED',
  'HR_INTERVIEW',
  'TECHNICAL_INTERVIEW',
  'FINAL_INTERVIEW',
  'OFFER',
  'REJECTED',
  'WITHDRAWN',
] as const

export type ApplicationStatus = (typeof APPLICATION_STATUSES)[number]
export type WorkMode = 'REMOTE' | 'HYBRID' | 'ONSITE'
export type CompanyType = 'STARTUP' | 'SCALEUP' | 'CORPORATE' | 'CONSULTING' | 'OTHER'
export type SalaryPeriod = 'YEARLY' | 'MONTHLY' | 'HOURLY'

export interface CatalogItem {
  id: number
  name: string
}

export interface Company extends CatalogItem {
  website: string | null
  companyType: CompanyType
  industry: string | null
}

export interface StatusHistory {
  id: number
  status: ApplicationStatus
  changedAt: string
}

export interface JobApplicationSummary {
  id: number
  company: Company
  positionTitle: string
  status: ApplicationStatus
  appliedDate: string | null
  source: CatalogItem
  workMode: WorkMode
  technologies: CatalogItem[]
  createdAt: string
  updatedAt: string
}

export interface JobApplicationDetail extends JobApplicationSummary {
  jobUrl: string | null
  location: string | null
  salaryMin: string | null
  salaryMax: string | null
  currency: string | null
  salaryPeriod: SalaryPeriod | null
  notes: string | null
  history: StatusHistory[]
}

export interface JobApplicationPage {
  items: JobApplicationSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface NewCompanyRequest {
  name: string
  website?: string | null
  companyType: CompanyType
  industry?: string | null
}

export interface ApplicationWriteRequest {
  companyId?: number
  newCompany?: NewCompanyRequest
  positionTitle: string
  jobUrl?: string | null
  appliedDate?: string | null
  sourceId: number
  workMode: WorkMode
  location?: string | null
  salaryMin?: string | null
  salaryMax?: string | null
  currency?: string | null
  salaryPeriod?: SalaryPeriod | null
  notes?: string | null
  technologyIds: number[]
}

export interface CreateApplicationRequest extends ApplicationWriteRequest {
  status: ApplicationStatus
}

export type UpdateApplicationRequest = ApplicationWriteRequest

export interface ChangeStatusRequest {
  status: ApplicationStatus
  appliedDate?: string | null
}

export type ExtractionConfidence = 'HIGH' | 'MEDIUM' | 'LOW'

export interface JobOfferExtraction {
  canonicalUrl: string
  positionTitle: string | null
  company: { name: string | null; website: string | null; existingCompanyId: number | null } | null
  sourceId: number | null
  workMode: WorkMode | null
  location: string | null
  salary: { min: string | null; max: string | null; currency: string; period: SalaryPeriod } | null
  warnings: string[]
  confidence: Record<string, ExtractionConfidence>
}

export interface ApplicationListParams {
  status?: ApplicationStatus[]
  sourceId?: number
  companyId?: number
  technologyId?: number
  page?: number
  size?: number
  sortBy?: 'createdAt' | 'updatedAt' | 'appliedDate' | 'positionTitle' | 'status'
  direction?: 'ASC' | 'DESC'
}

export interface ProblemDetails {
  status?: number
  title?: string
  detail?: string
  errors?: Record<string, string>
}

export interface CurrentUser {
  id: number
  fullName: string
  email: string
  emailVerified: boolean
  authenticationMethods: Array<'PASSWORD' | 'GOOGLE'>
}

export interface GenericMessage {
  message: string
}

export interface CsrfTokenResponse {
  token: string
  headerName: string
}

export type AnalyticsPeriod = 'WEEK' | 'MONTH'
export type AnalyticsFunnelStatus = 'APPLIED' | 'RESPONSE_RECEIVED' | 'HR_INTERVIEW' | 'TECHNICAL_INTERVIEW' | 'FINAL_INTERVIEW' | 'OFFER'

export interface AnalyticsSummary {
  totalApplications: number
  appliedApplications: number
  responseCount: number
  responseRate: number
  interviewCount: number
  interviewRate: number
  offerCount: number
  offerRate: number
  rejectionCount: number
  rejectionRate: number
}

export interface FunnelAnalytics {
  stages: Array<{ status: AnalyticsFunnelStatus; count: number }>
}

export interface ApplicationsOverTime {
  period: AnalyticsPeriod
  buckets: Array<{ startDate: string; applicationCount: number }>
}

export interface DimensionAnalyticsItem {
  id: number
  name: string
  applicationCount: number
  responseCount: number
  responseRate: number
  interviewCount: number
  interviewRate: number
  offerCount: number
  offerRate: number
  rejectionCount: number
  rejectionRate: number
}

export interface DimensionAnalytics {
  items: DimensionAnalyticsItem[]
}

export interface ResponseTimeAnalytics {
  sampleSize: number
  averageDays: number | null
  medianDays: number | null
}
