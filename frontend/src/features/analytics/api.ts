import { useQuery } from '@tanstack/react-query'
import { apiRequest } from '@/lib/api-client'
import type { AnalyticsPeriod, AnalyticsSummary, ApplicationsOverTime, DimensionAnalytics, FunnelAnalytics, ResponseTimeAnalytics } from '@/types/api'

export const analyticsKeys = {
  all: ['analytics'] as const,
  summary: ['analytics', 'summary'] as const,
  funnel: ['analytics', 'funnel'] as const,
  applicationsOverTime: (period: AnalyticsPeriod) => ['analytics', 'applications-over-time', period] as const,
  sources: ['analytics', 'sources'] as const,
  technologies: ['analytics', 'technologies'] as const,
  responseTime: ['analytics', 'response-time'] as const,
}

export const getAnalyticsSummary = (signal?: AbortSignal) => apiRequest<AnalyticsSummary>('/analytics/summary', { signal })
export const getAnalyticsFunnel = (signal?: AbortSignal) => apiRequest<FunnelAnalytics>('/analytics/funnel', { signal })
export const getApplicationsOverTime = (period: AnalyticsPeriod, signal?: AbortSignal) => apiRequest<ApplicationsOverTime>(`/analytics/applications-over-time?period=${period}`, { signal })
export const getSourceAnalytics = (signal?: AbortSignal) => apiRequest<DimensionAnalytics>('/analytics/sources', { signal })
export const getTechnologyAnalytics = (signal?: AbortSignal) => apiRequest<DimensionAnalytics>('/analytics/technologies', { signal })
export const getResponseTimeAnalytics = (signal?: AbortSignal) => apiRequest<ResponseTimeAnalytics>('/analytics/response-time', { signal })

export function useAnalyticsSummary() { return useQuery({ queryKey: analyticsKeys.summary, queryFn: ({ signal }) => getAnalyticsSummary(signal) }) }
export function useAnalyticsFunnel() { return useQuery({ queryKey: analyticsKeys.funnel, queryFn: ({ signal }) => getAnalyticsFunnel(signal) }) }
export function useApplicationsOverTime(period: AnalyticsPeriod) { return useQuery({ queryKey: analyticsKeys.applicationsOverTime(period), queryFn: ({ signal }) => getApplicationsOverTime(period, signal) }) }
export function useSourceAnalytics() { return useQuery({ queryKey: analyticsKeys.sources, queryFn: ({ signal }) => getSourceAnalytics(signal) }) }
export function useTechnologyAnalytics() { return useQuery({ queryKey: analyticsKeys.technologies, queryFn: ({ signal }) => getTechnologyAnalytics(signal) }) }
export function useResponseTimeAnalytics() { return useQuery({ queryKey: analyticsKeys.responseTime, queryFn: ({ signal }) => getResponseTimeAnalytics(signal) }) }
