import { Button, Card } from '@/components/ui'
import type { DimensionAnalytics, DimensionAnalyticsItem } from '@/types/api'
import { useSourceAnalytics, useTechnologyAnalytics } from './api'

const percentage = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })

function Loading({ label }: { label: string }) {
  return <div role="status" className="grid h-44 place-items-center rounded-[12px] bg-muted"><span className="sr-only">{label}</span></div>
}

function Failure({ title, retry }: { title: string; retry: () => void }) {
  return <div role="alert"><p className="font-semibold">{title} could not be loaded</p><p className="mt-1 text-sm text-muted-foreground">Your other analytics remain available.</p><Button className="mt-3 border bg-white text-foreground shadow-xs hover:bg-[#eee8e1]" onClick={retry}>Try again</Button></div>
}

function MetricCells({ item }: { item: DimensionAnalyticsItem }) {
  return <><td className="px-3 py-2 text-right tabular-nums">{item.applicationCount}</td><td className="px-3 py-2 text-right tabular-nums">{item.responseCount}</td><td className="px-3 py-2 text-right tabular-nums">{percentage.format(item.responseRate)}%</td><td className="px-3 py-2 text-right tabular-nums">{item.interviewCount}</td><td className="px-3 py-2 text-right tabular-nums">{percentage.format(item.interviewRate)}%</td><td className="px-3 py-2 text-right tabular-nums">{item.offerCount}</td><td className="px-3 py-2 text-right tabular-nums">{percentage.format(item.offerRate)}%</td><td className="px-3 py-2 text-right tabular-nums">{item.rejectionCount}</td><td className="px-3 py-2 text-right tabular-nums">{percentage.format(item.rejectionRate)}%</td></>
}

function Comparison({ title, empty, data, overlap = false }: { title: string; empty: string; data: DimensionAnalytics; overlap?: boolean }) {
  if (data.items.length === 0) return <p className="text-sm text-muted-foreground">{empty}</p>
  const max = Math.max(1, ...data.items.map(({ applicationCount }) => applicationCount))
  return <>
    <p className="mb-4 text-xs leading-5 text-muted-foreground">Bar lengths compare application counts, not shares.{overlap ? ' Technologies can overlap because one application may appear in multiple rows.' : ''}</p>
    <div aria-hidden="true" className="space-y-3">{data.items.map((item) => <div key={item.id}><div className="mb-1 flex justify-between gap-3 text-xs"><span className="min-w-0 break-words">{item.name}</span><span className="tabular-nums">{item.applicationCount}</span></div><div className="h-2 rounded-full bg-muted"><div className="h-full rounded-full bg-foreground" style={{ width: `${(item.applicationCount / max) * 100}%` }} /></div></div>)}</div>
    <div role="region" aria-label={`${title} data table`} tabIndex={0} className="mt-5 overflow-x-auto rounded-[10px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><table className="w-full min-w-[920px] text-left text-sm"><caption className="sr-only">Complete {title.toLowerCase()} analytics</caption><thead><tr className="border-b"><th scope="col" className="px-3 py-2 font-medium">Name</th><th scope="col" className="px-3 py-2 text-right font-medium">Applications</th><th scope="col" className="px-3 py-2 text-right font-medium">Responses</th><th scope="col" className="px-3 py-2 text-right font-medium">Response rate</th><th scope="col" className="px-3 py-2 text-right font-medium">Interviews</th><th scope="col" className="px-3 py-2 text-right font-medium">Interview rate</th><th scope="col" className="px-3 py-2 text-right font-medium">Offers</th><th scope="col" className="px-3 py-2 text-right font-medium">Offer rate</th><th scope="col" className="px-3 py-2 text-right font-medium">Rejections</th><th scope="col" className="px-3 py-2 text-right font-medium">Rejection rate</th></tr></thead><tbody>{data.items.map((item) => <tr key={item.id} className="border-b last:border-0"><th scope="row" className="px-3 py-2 font-normal">{item.name}</th><MetricCells item={item} /></tr>)}</tbody></table></div>
  </>
}

function ComparisonPanel({ title, empty, overlap, pending, error, retry, data }: { title: string; empty: string; overlap?: boolean; pending: boolean; error: boolean; retry: () => void; data?: DimensionAnalytics }) {
  return <Card className="min-w-0 p-5 sm:p-6"><h2 className="text-lg font-semibold">{title}</h2><div className="mt-5">{pending ? <Loading label={`Loading ${title.toLowerCase()}`} /> : error || !data ? <Failure title={title} retry={retry} /> : <Comparison title={title} empty={empty} data={data} overlap={overlap} />}</div></Card>
}

export function AnalyticsComparisons() {
  const sources = useSourceAnalytics()
  const technologies = useTechnologyAnalytics()
  return <div className="grid gap-5 xl:grid-cols-2">
    <ComparisonPanel title="Sources" empty="No source analytics yet." pending={sources.isPending} error={Boolean(sources.error)} retry={() => void sources.refetch()} data={sources.data} />
    <ComparisonPanel title="Technologies" empty="No technology analytics yet." overlap pending={technologies.isPending} error={Boolean(technologies.error)} retry={() => void technologies.refetch()} data={technologies.data} />
  </div>
}
