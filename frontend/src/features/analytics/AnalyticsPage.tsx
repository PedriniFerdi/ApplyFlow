import { BarChart3, CircleX, MessageCircleReply, Send, Star, UsersRound } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Button, Card, ErrorPanel, Spinner } from '@/components/ui'
import type { AnalyticsSummary } from '@/types/api'
import { useAnalyticsSummary, useResponseTimeAnalytics } from './api'
import { AnalyticsProgress } from './AnalyticsProgress'

const percentage = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })
const decimal = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })

function rate(value: number, denominator: number) {
  return denominator === 0 ? 'Rate unavailable — no sent applications' : `${percentage.format(value)}% of ${denominator} sent ${denominator === 1 ? 'application' : 'applications'}`
}

function MetricCard({ label, value, detail, icon: Icon }: { label: string; value: string | number; detail: string; icon: typeof Send }) {
  return <Card className="min-w-0 p-4 sm:p-5">
    <div className="flex items-start justify-between gap-3">
      <div className="min-w-0"><dt className="text-sm font-medium text-muted-foreground">{label}</dt><dd className="mt-2 text-[30px] font-semibold leading-none tracking-[-0.04em]">{value}</dd></div>
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-[#eeeeed] text-foreground"><Icon size={19} strokeWidth={1.7} aria-hidden="true" /></span>
    </div>
    <p className="mt-3 text-xs leading-5 text-muted-foreground">{detail}</p>
  </Card>
}

function AnalyticsPanel({ title, children }: { title: string; children: ReactNode }) {
  return <Card className="p-5 sm:p-6"><h2 className="text-lg font-semibold">{title}</h2><div className="mt-5">{children}</div></Card>
}

function ResponseTimePanel() {
  const responseTime = useResponseTimeAnalytics()
  if (responseTime.isPending) return <AnalyticsPanel title="Response time"><div role="status" className="space-y-3"><span className="sr-only">Loading response time</span><span className="block h-14 rounded-[12px] bg-muted" /><span className="block h-14 rounded-[12px] bg-muted" /></div></AnalyticsPanel>
  if (responseTime.error) return <AnalyticsPanel title="Response time"><div role="alert"><p className="font-semibold">Response time could not be loaded</p><p className="mt-1 text-sm text-muted-foreground">Your other analytics remain available.</p><Button className="mt-3 border bg-white text-foreground shadow-xs hover:bg-[#eee8e1]" disabled={responseTime.isFetching} onClick={() => void responseTime.refetch()}>{responseTime.isFetching ? 'Retrying…' : 'Try again'}</Button></div></AnalyticsPanel>
  if (responseTime.data.sampleSize === 0) return <AnalyticsPanel title="Response time"><p className="text-sm text-muted-foreground">No response-time sample yet. Timing appears after a sent application receives its first response.</p></AnalyticsPanel>
  if (responseTime.data.averageDays == null || responseTime.data.medianDays == null) return <AnalyticsPanel title="Response time"><p className="text-sm text-muted-foreground">Complete response-time metrics are unavailable for this sample of {responseTime.data.sampleSize} measured responses.</p></AnalyticsPanel>

  const days = (value: number) => `${decimal.format(value)} ${value === 1 ? 'day' : 'days'}`
  return <AnalyticsPanel title="Response time"><dl className="grid gap-4 sm:grid-cols-3">
    <div><dt className="text-sm text-muted-foreground">Average</dt><dd className="mt-1 text-2xl font-semibold">{days(responseTime.data.averageDays)}</dd></div>
    <div><dt className="text-sm text-muted-foreground">Median</dt><dd className="mt-1 text-2xl font-semibold">{days(responseTime.data.medianDays)}</dd></div>
    <div><dt className="text-sm text-muted-foreground">Responses measured</dt><dd className="mt-1 text-2xl font-semibold">{responseTime.data.sampleSize}</dd></div>
  </dl></AnalyticsPanel>
}

function AnalyticsOverview({ summary }: { summary: AnalyticsSummary }) {
  const sent = summary.appliedApplications
  return <div className="space-y-5">
    <dl aria-label="Analytics summary" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
      <MetricCard label="Applications sent" value={sent} detail={`${summary.totalApplications} total ${summary.totalApplications === 1 ? 'application' : 'applications'} tracked`} icon={Send} />
      <MetricCard label="Interviews" value={summary.interviewCount} detail={rate(summary.interviewRate, sent)} icon={UsersRound} />
      <MetricCard label="Offers" value={summary.offerCount} detail={rate(summary.offerRate, sent)} icon={Star} />
      <MetricCard label="Response rate" value={sent === 0 ? '—' : `${percentage.format(summary.responseRate)}%`} detail={sent === 0 ? 'Rate unavailable — no sent applications' : `${summary.responseCount} ${summary.responseCount === 1 ? 'response' : 'responses'} from ${sent} sent ${sent === 1 ? 'application' : 'applications'}`} icon={MessageCircleReply} />
      <MetricCard label="Rejections" value={summary.rejectionCount} detail={rate(summary.rejectionRate, sent)} icon={CircleX} />
    </dl>
    <AnalyticsProgress />
    <ResponseTimePanel />
  </div>
}

export default function AnalyticsPage() {
  const summary = useAnalyticsSummary()
  return <>
    <header className="mb-8"><h1 className="font-heading text-[40px] font-semibold leading-none tracking-[-0.05em] sm:text-[48px]">Analytics</h1><p className="mt-3 max-w-2xl text-[16px] leading-6 text-muted-foreground">Understand the outcomes behind your application activity.</p></header>
    {summary.isPending ? <Spinner label="Loading analytics" /> : summary.error ? <ErrorPanel title="Analytics could not be loaded" message={summary.error.message} onRetry={() => void summary.refetch()} /> : summary.data.totalApplications === 0 ? <Card className="grid min-h-72 place-items-center p-8 text-center"><div><BarChart3 className="mx-auto text-muted-foreground" size={34} aria-hidden="true" /><h2 className="mt-4 text-xl font-semibold">No analytics yet</h2><p className="mt-2 text-sm text-muted-foreground">Add an application to begin building your analytics.</p><Link to="/applications/new" className="mt-5 inline-flex min-h-11 items-center rounded-[11px] bg-primary px-4 text-sm font-semibold text-primary-foreground">Add application</Link></div></Card> : <AnalyticsOverview summary={summary.data} />}
  </>
}
