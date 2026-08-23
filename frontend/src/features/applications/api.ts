import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { analyticsKeys } from '@/features/analytics/api'
import { apiRequest, prefetchCsrfToken } from '@/lib/api-client'
import type { ApplicationListParams, ApplicationStatus, CatalogItem, ChangeStatusRequest, Company, CreateApplicationRequest, JobApplicationDetail, JobApplicationPage, JobOfferExtraction, UpdateApplicationRequest } from '@/types/api'
import type { TrackerGroup } from './model'

const TRACKER_PAGE_SIZE = 8

export const applicationKeys = {
  all: ['applications'] as const,
  lists: () => ['applications', 'list'] as const,
  list: (params: ApplicationListParams) => ['applications', 'list', params] as const,
  tracker: ['applications', 'tracker'] as const,
  trackerStage: (group: TrackerGroup) => ['applications', 'tracker', group] as const,
  detail: (id: number) => ['applications', 'detail', id] as const,
}

export const catalogKeys = {
  companies: (query: string) => ['catalogs', 'companies', query] as const,
  sources: ['catalogs', 'sources'] as const,
  technologies: ['catalogs', 'technologies'] as const,
}

export function applicationSearchParams(params: ApplicationListParams): URLSearchParams {
  const search = new URLSearchParams()
  for (const status of params.status ?? []) search.append('status', status)
  if (params.sourceId) search.set('sourceId', String(params.sourceId))
  if (params.companyId) search.set('companyId', String(params.companyId))
  if (params.technologyId) search.set('technologyId', String(params.technologyId))
  search.set('page', String(params.page ?? 0))
  search.set('size', String(params.size ?? 20))
  search.set('sortBy', params.sortBy ?? 'createdAt')
  search.set('direction', params.direction ?? 'DESC')
  return search
}

export function getApplications(params: ApplicationListParams, signal?: AbortSignal) {
  return apiRequest<JobApplicationPage>(`/applications?${applicationSearchParams(params)}`, { signal })
}

export function getTrackerPage(statuses: readonly ApplicationStatus[], page: number, signal?: AbortSignal) {
  return getApplications({ status: [...statuses], page, size: TRACKER_PAGE_SIZE, sortBy: 'updatedAt', direction: 'DESC' }, signal)
}

export const getApplication = (id: number, signal?: AbortSignal) => apiRequest<JobApplicationDetail>(`/applications/${id}`, { signal })
export const createApplication = (request: CreateApplicationRequest) => apiRequest<JobApplicationDetail>('/applications', { method: 'POST', body: request })
export const updateApplication = ({ id, request }: { id: number; request: UpdateApplicationRequest }) => apiRequest<JobApplicationDetail>(`/applications/${id}`, { method: 'PUT', body: request })
export const changeApplicationStatus = ({ id, request }: { id: number; request: ChangeStatusRequest }) => apiRequest<JobApplicationDetail>(`/applications/${id}/status`, { method: 'PATCH', body: request })
export const deleteApplication = (id: number) => apiRequest<void>(`/applications/${id}`, { method: 'DELETE' })
export const getCompanies = (query: string, signal?: AbortSignal) => apiRequest<Company[]>(`/companies?query=${encodeURIComponent(query)}`, { signal })
export const getSources = (signal?: AbortSignal) => apiRequest<CatalogItem[]>('/sources', { signal })
export const getTechnologies = (signal?: AbortSignal) => apiRequest<CatalogItem[]>('/technologies', { signal })
export const createTechnology = (name: string) => apiRequest<CatalogItem>('/technologies', { method: 'POST', body: { name } })
export const extractJobOffer = (url: string, signal?: AbortSignal) => apiRequest<JobOfferExtraction>('/job-offers/extract', { method: 'POST', body: { url }, signal })

export async function prefetchNewApplicationResources(client: QueryClient) {
  await Promise.all([
    prefetchCsrfToken(),
    client.prefetchQuery({ queryKey: catalogKeys.companies(''), queryFn: ({ signal }) => getCompanies('', signal), staleTime: 30_000 }),
    client.prefetchQuery({ queryKey: catalogKeys.sources, queryFn: ({ signal }) => getSources(signal), staleTime: 300_000 }),
    client.prefetchQuery({ queryKey: catalogKeys.technologies, queryFn: ({ signal }) => getTechnologies(signal), staleTime: 300_000 }),
  ])
}

export function useApplications(params: ApplicationListParams) {
  return useQuery({ queryKey: applicationKeys.list(params), queryFn: ({ signal }) => getApplications(params, signal) })
}

export function useTracker(group: TrackerGroup, statuses: readonly ApplicationStatus[]) {
  return useInfiniteQuery({
    queryKey: applicationKeys.trackerStage(group),
    queryFn: ({ pageParam, signal }) => getTrackerPage(statuses, pageParam, signal),
    initialPageParam: 0,
    getNextPageParam: (lastPage) => lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
  })
}

export function useApplication(id: number) {
  return useQuery({ queryKey: applicationKeys.detail(id), queryFn: ({ signal }) => getApplication(id, signal), enabled: Number.isInteger(id) && id > 0 })
}

export function useCompanies(query = '') { return useQuery({ queryKey: catalogKeys.companies(query), queryFn: ({ signal }) => getCompanies(query, signal), staleTime: 30_000 }) }
export function useSources() { return useQuery({ queryKey: catalogKeys.sources, queryFn: ({ signal }) => getSources(signal), staleTime: 300_000 }) }
export function useTechnologies() { return useQuery({ queryKey: catalogKeys.technologies, queryFn: ({ signal }) => getTechnologies(signal), staleTime: 300_000 }) }

export async function invalidateApplicationData(client: QueryClient, detail?: JobApplicationDetail) {
  if (detail) client.setQueryData(applicationKeys.detail(detail.id), detail)
  await Promise.all([
    client.invalidateQueries({ queryKey: applicationKeys.lists() }),
    client.invalidateQueries({ queryKey: applicationKeys.tracker }),
    client.invalidateQueries({ queryKey: analyticsKeys.all }),
  ])
}

function useApplicationInvalidation() {
  const client = useQueryClient()
  return (detail?: JobApplicationDetail) => invalidateApplicationData(client, detail)
}

export function useCreateApplication() {
  const invalidate = useApplicationInvalidation()
  return useMutation({ mutationFn: createApplication, onSuccess: invalidate })
}

export function useUpdateApplication() {
  const invalidate = useApplicationInvalidation()
  return useMutation({ mutationFn: updateApplication, onSuccess: invalidate })
}

export function useChangeStatus() {
  const invalidate = useApplicationInvalidation()
  return useMutation({ mutationFn: changeApplicationStatus, onSuccess: invalidate })
}

export function useDeleteApplication() {
  const client = useQueryClient()
  return useMutation({ mutationFn: deleteApplication, onSuccess: async (_, id) => {
    client.removeQueries({ queryKey: applicationKeys.detail(id) })
    await invalidateApplicationData(client)
  } })
}

export function useCreateTechnology() {
  const client = useQueryClient()
  return useMutation({ mutationFn: createTechnology, onSuccess: (item) => {
    client.setQueryData<CatalogItem[]>(catalogKeys.technologies, (current = []) => [...current.filter(({ id }) => id !== item.id), item].sort((a, b) => a.name.localeCompare(b.name)))
  } })
}
