import { BarChart3 } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Card, ErrorPanel, Spinner } from '@/components/ui'
import { useAnalyticsSummary } from './api'

export default function AnalyticsPage() {
  const summary = useAnalyticsSummary()
  return <>
    <header className="mb-8"><h1 className="font-heading text-[40px] font-semibold leading-none tracking-[-0.05em] sm:text-[48px]">Analytics</h1><p className="mt-3 max-w-2xl text-[16px] leading-6 text-muted-foreground">Understand the outcomes behind your application activity.</p></header>
    {summary.isPending ? <Spinner label="Loading analytics" /> : summary.error ? <ErrorPanel title="Analytics could not be loaded" message={summary.error.message} onRetry={() => void summary.refetch()} /> : summary.data.totalApplications === 0 ? <Card className="grid min-h-72 place-items-center p-8 text-center"><div><BarChart3 className="mx-auto text-muted-foreground" size={34} aria-hidden="true" /><h2 className="mt-4 text-xl font-semibold">No analytics yet</h2><p className="mt-2 text-sm text-muted-foreground">Add an application to begin building your analytics.</p><Link to="/applications/new" className="mt-5 inline-flex min-h-11 items-center rounded-[11px] bg-primary px-4 text-sm font-semibold text-primary-foreground">Add application</Link></div></Card> : <Card className="p-6"><h2 className="text-xl font-semibold">Analytics data is ready</h2><p className="mt-2 text-sm text-muted-foreground">Insights are based on {summary.data.totalApplications} {summary.data.totalApplications === 1 ? 'application' : 'applications'}.</p></Card>}
  </>
}
