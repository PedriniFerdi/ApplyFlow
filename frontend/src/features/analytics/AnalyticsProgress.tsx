import { useState, type ReactNode } from 'react'
import { Button, Card, Label, Select } from '@/components/ui'
import type { AnalyticsFunnelStatus, AnalyticsPeriod, ApplicationsOverTime, FunnelAnalytics } from '@/types/api'
import { useAnalyticsFunnel, useApplicationsOverTime } from './api'

const dateFormat = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
const milestoneLabels: Record<AnalyticsFunnelStatus, string> = {
  APPLIED: 'Applied', RESPONSE_RECEIVED: 'Response received', HR_INTERVIEW: 'HR interview',
  TECHNICAL_INTERVIEW: 'Technical interview', FINAL_INTERVIEW: 'Final interview', OFFER: 'Offer',
}

function formatBucketDate(value: string) {
  return dateFormat.format(new Date(`${value}T00:00:00Z`))
}

function Panel({ title, control, children }: { title: string; control?: ReactNode; children: ReactNode }) {
  return <Card className="min-w-0 p-5 sm:p-6"><div className="flex flex-wrap items-end justify-between gap-3"><h2 className="text-lg font-semibold">{title}</h2>{control}</div><div className="mt-5">{children}</div></Card>
}

function Loading({ label }: { label: string }) {
  return <div role="status" className="grid h-44 place-items-center rounded-[12px] bg-muted"><span className="sr-only">{label}</span></div>
}

function Failure({ title, retry }: { title: string; retry: () => void }) {
  return <div role="alert"><p className="font-semibold">{title}</p><p className="mt-1 text-sm text-muted-foreground">Your other analytics remain available.</p><Button className="mt-3 border bg-white text-foreground shadow-xs hover:bg-[#eee8e1]" onClick={retry}>Try again</Button></div>
}

function TimeSeries({ data }: { data: ApplicationsOverTime }) {
  if (data.buckets.length === 0) return <p className="text-sm text-muted-foreground">No dated applications for this grouping.</p>
  const max = Math.max(1, ...data.buckets.map(({ applicationCount }) => applicationCount))
  const points = data.buckets.map(({ applicationCount }, index) => {
    const x = data.buckets.length === 1 ? 50 : 4 + (index / (data.buckets.length - 1)) * 92
    return `${x},${36 - (applicationCount / max) * 32}`
  }).join(' ')
  return <>
    <svg aria-hidden="true" viewBox="0 0 100 40" preserveAspectRatio="none" className="h-40 w-full overflow-visible rounded-[10px] bg-muted/40 p-2 text-[#5B5CE2]">
      <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.25" vectorEffect="non-scaling-stroke" />
      {points.split(' ').map((point) => { const [cx, cy] = point.split(','); return <circle key={point} cx={cx} cy={cy} r="1.2" fill="currentColor" /> })}
    </svg>
    <div className="mt-4 overflow-x-auto"><table className="w-full min-w-80 text-left text-sm"><caption className="sr-only">All-time applications grouped by {data.period.toLowerCase()}</caption><thead><tr className="border-b"><th scope="col" className="py-2 font-medium">Period start</th><th scope="col" className="py-2 text-right font-medium">Applications</th></tr></thead><tbody>{data.buckets.map((bucket) => <tr key={bucket.startDate} className="border-b last:border-0"><th scope="row" className="py-2 font-normal"><time dateTime={bucket.startDate}>{formatBucketDate(bucket.startDate)}</time></th><td className="py-2 text-right tabular-nums">{bucket.applicationCount}</td></tr>)}</tbody></table></div>
  </>
}

function Milestones({ data }: { data: FunnelAnalytics }) {
  if (data.stages.every(({ count }) => count === 0)) return <p className="text-sm text-muted-foreground">No recorded milestones yet.</p>
  const max = Math.max(1, ...data.stages.map(({ count }) => count))
  return <><p className="mb-4 text-xs leading-5 text-muted-foreground">Counts are recorded milestones, not conversion rates.</p>
    <div aria-hidden="true" className="space-y-3">{data.stages.map(({ status, count }) => <div key={status}><div className="mb-1 flex justify-between gap-3 text-xs"><span>{milestoneLabels[status]}</span><span className="tabular-nums">{count}</span></div><div className="h-2 rounded-full bg-muted"><div className="h-full rounded-full bg-[#2F72E8]" style={{ width: `${(count / max) * 100}%` }} /></div></div>)}</div>
    <table className="mt-5 w-full text-left text-sm"><caption className="sr-only">Recorded application milestone counts</caption><thead><tr className="border-b"><th scope="col" className="py-2 font-medium">Milestone</th><th scope="col" className="py-2 text-right font-medium">Recorded count</th></tr></thead><tbody>{data.stages.map(({ status, count }) => <tr key={status} className="border-b last:border-0"><th scope="row" className="py-2 font-normal">{milestoneLabels[status]}</th><td className="py-2 text-right tabular-nums">{count}</td></tr>)}</tbody></table>
  </>
}

function ApplicationsOverTimePanel() {
  const [period, setPeriod] = useState<AnalyticsPeriod>('WEEK')
  const query = useApplicationsOverTime(period)
  const control = <div><Label htmlFor="analytics-period">Group all-time history by</Label><Select id="analytics-period" className="min-w-32" value={period} onChange={(event) => setPeriod(event.target.value as AnalyticsPeriod)}><option value="WEEK">Week</option><option value="MONTH">Month</option></Select></div>
  return <Panel title="Applications over time" control={control}>{query.isPending ? <Loading label="Loading applications over time" /> : query.error ? <Failure title="Applications over time could not be loaded" retry={() => void query.refetch()} /> : <TimeSeries data={query.data} />}</Panel>
}

function MilestonesPanel() {
  const query = useAnalyticsFunnel()
  return <Panel title="Recorded milestones">{query.isPending ? <Loading label="Loading recorded milestones" /> : query.error ? <Failure title="Recorded milestones could not be loaded" retry={() => void query.refetch()} /> : <Milestones data={query.data} />}</Panel>
}

export function AnalyticsProgress() {
  return <div className="grid gap-5 xl:grid-cols-[1.35fr_1fr]"><ApplicationsOverTimePanel /><MilestonesPanel /></div>
}
