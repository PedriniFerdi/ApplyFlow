import { Archive, ArrowDownUp, ArrowLeft, BadgeCheck, Bookmark, BriefcaseBusiness, ChevronDown, ExternalLink, Funnel, Pencil, Plus, Send, Trash2, Users } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, Navigate, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ApiProblem } from '@/lib/api-client'
import { Badge, Button, Card, ErrorPanel, Label, Select, Spinner } from '@/components/ui'
import { APPLICATION_STATUSES, type ApplicationListParams, type ApplicationStatus, type JobApplicationSummary } from '@/types/api'
import { ApplicationForm } from './ApplicationForm'
import { PageHeader } from './PageHeader'
import { StatusControl } from './StatusControl'
import { ACTIVE_TRACKER_GROUPS, groupApplications, STATUS_LABELS, formatDate, formatInstant, type TrackerGroup } from './model'
import { useApplication, useApplications, useCompanies, useDeleteApplication, useSources, useTechnologies, useTracker, useUpdateApplication } from './api'

const primaryLinkClass = 'inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-[11px] bg-primary px-4 text-sm font-semibold text-primary-foreground shadow-[inset_0_1px_0_rgba(255,255,255,.16),0_3px_8px_rgba(0,0,0,.12)] transition-[background-color,color,border-color,transform,box-shadow] duration-200 hover:bg-[#2c2c2c] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 active:translate-y-px'
const secondaryLinkClass = 'inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-[11px] border bg-background px-4 text-sm font-semibold text-foreground shadow-xs transition-[background-color,color,border-color,transform,box-shadow] duration-200 hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 active:translate-y-px'

function statusTone(status: ApplicationStatus): 'neutral' | 'blue' | 'green' | 'red' | 'amber' {
  if (status === 'OFFER') return 'green'
  if (status === 'REJECTED' || status === 'WITHDRAWN') return 'red'
  if (status.includes('INTERVIEW') || status === 'RESPONSE_RECEIVED') return 'amber'
  if (status === 'APPLIED') return 'blue'
  return 'neutral'
}

function ApplicationRow({ item }: { item: JobApplicationSummary }) {
  return <Link to={`/applications/${item.id}`} className="grid gap-3 rounded-[14px] border border-[#786a5d]/15 bg-white/60 p-4 shadow-[0_6px_18px_rgba(91,58,36,.04)] transition hover:-translate-y-0.5 hover:border-foreground/20 hover:bg-white/80 hover:shadow-[0_10px_26px_rgba(91,58,36,.08)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring md:grid-cols-[minmax(220px,1.5fr)_1fr_1fr_auto] md:items-center">
    <div><p className="font-semibold">{item.positionTitle}</p><p className="text-sm text-muted-foreground">{item.company.name}</p></div>
    <div><Badge tone={statusTone(item.status)}>{STATUS_LABELS[item.status]}</Badge><p className="mt-1 text-xs text-muted-foreground">{formatDate(item.appliedDate)}</p></div>
    <div className="text-sm"><p>{item.source.name} · {item.workMode.toLowerCase()}</p><p className="mt-1 truncate text-xs text-muted-foreground">{item.technologies.map(({ name }) => name).join(', ') || 'No technologies'}</p></div>
    <span className="text-sm font-medium text-foreground">View</span>
  </Link>
}

export function ApplicationsPage() {
  const [search, setSearch] = useSearchParams()
  const statuses = search.getAll('status').filter((value): value is ApplicationStatus => APPLICATION_STATUSES.includes(value as ApplicationStatus))
  const params: ApplicationListParams = {
    status: statuses, sourceId: Number(search.get('sourceId')) || undefined, companyId: Number(search.get('companyId')) || undefined,
    technologyId: Number(search.get('technologyId')) || undefined, page: Number(search.get('page')) || 0, size: 20,
    sortBy: (search.get('sortBy') as ApplicationListParams['sortBy']) || 'createdAt', direction: (search.get('direction') as 'ASC' | 'DESC') || 'DESC',
  }
  const query = useApplications(params), sources = useSources(), technologies = useTechnologies(), companies = useCompanies('')
  const hasFilters = statuses.length > 0 || params.sourceId || params.companyId || params.technologyId

  function update(key: string, value?: string) {
    const next = new URLSearchParams(search)
    if (value) next.set(key, value); else next.delete(key)
    if (key !== 'page') next.delete('page')
    setSearch(next)
  }
  function toggleStatus(status: ApplicationStatus) {
    const next = new URLSearchParams(search); next.delete('status')
    const updated = statuses.includes(status) ? statuses.filter((item) => item !== status) : [...statuses, status]
    updated.forEach((item) => next.append('status', item)); next.delete('page'); setSearch(next)
  }

  const applicationCount = query.data?.totalElements ?? 0
  const activeFilterCount = statuses.length + Number(Boolean(params.sourceId)) + Number(Boolean(params.companyId)) + Number(Boolean(params.technologyId))

  return <><PageHeader title="Applications" description="Add and manage your job applications." className="mb-0 border-b border-[#786a5d]/15 pb-8" />
    <div className="relative flex min-h-20 flex-wrap items-center justify-between gap-3 border-b border-[#786a5d]/15 py-3">
      <p className="text-[15px] font-medium text-foreground">{applicationCount} application{applicationCount === 1 ? '' : 's'}</p>
      <div className="flex items-center gap-2">
        <details className="group relative">
          <summary className="flex min-h-11 cursor-pointer select-none items-center gap-2 rounded-[11px] border border-[#786a5d]/20 bg-white/65 px-4 text-[15px] font-medium text-[#4b4b4a] shadow-xs transition hover:bg-white/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <Funnel size={18} strokeWidth={1.6} aria-hidden="true" />
            <span>Filters{activeFilterCount > 0 ? ` (${activeFilterCount})` : ''}</span>
            <ChevronDown className="transition-transform group-open:rotate-180" size={16} strokeWidth={1.7} aria-hidden="true" />
          </summary>
          <div className="absolute right-0 top-[calc(100%+.65rem)] z-20 w-[min(540px,calc(100vw-2.5rem))] rounded-[15px] border border-[#786a5d]/20 bg-[#fffdfa] p-5 shadow-[0_18px_45px_rgba(73,47,30,.14)]">
            <div className="grid gap-4 md:grid-cols-2">
              <div><Label>Statuses</Label><div className="max-h-40 space-y-1 overflow-auto rounded-[10px] border p-2">{APPLICATION_STATUSES.map((status) => <label key={status} className="flex min-h-8 items-center gap-2 rounded-md px-1 text-sm hover:bg-muted"><input type="checkbox" checked={statuses.includes(status)} onChange={() => toggleStatus(status)} />{STATUS_LABELS[status]}</label>)}</div></div>
              <div><Label htmlFor="source-filter">Source</Label><Select id="source-filter" value={params.sourceId ?? ''} onChange={(event) => update('sourceId', event.target.value)}><option value="">All sources</option>{sources.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></div>
              <div><Label htmlFor="company-filter">Company</Label><Select id="company-filter" value={params.companyId ?? ''} onChange={(event) => update('companyId', event.target.value)}><option value="">All companies</option>{companies.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></div>
              <div><Label htmlFor="tech-filter">Technology</Label><Select id="tech-filter" value={params.technologyId ?? ''} onChange={(event) => update('technologyId', event.target.value)}><option value="">All technologies</option>{technologies.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></div>
            </div>
            {hasFilters && <Button className="mt-4 border bg-background text-foreground shadow-xs hover:bg-muted" onClick={() => setSearch(new URLSearchParams())}>Clear filters</Button>}
          </div>
        </details>
        <details className="group relative">
          <summary className="flex min-h-11 cursor-pointer select-none items-center gap-2 rounded-[11px] border border-[#786a5d]/20 bg-white/65 px-4 text-[15px] font-medium text-[#4b4b4a] shadow-xs transition hover:bg-white/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <ArrowDownUp size={18} strokeWidth={1.6} aria-hidden="true" />
            <span>Sort</span>
            <ChevronDown className="transition-transform group-open:rotate-180" size={16} strokeWidth={1.7} aria-hidden="true" />
          </summary>
          <div className="absolute right-0 top-[calc(100%+.65rem)] z-20 w-[270px] space-y-3 rounded-[15px] border border-[#786a5d]/20 bg-[#fffdfa] p-4 shadow-[0_18px_45px_rgba(73,47,30,.14)]">
            <div><Label htmlFor="sort-field">Sort by</Label><Select id="sort-field" value={params.sortBy} onChange={(event) => update('sortBy', event.target.value)}><option value="createdAt">Created</option><option value="updatedAt">Updated</option><option value="appliedDate">Applied date</option><option value="positionTitle">Position</option><option value="status">Status</option></Select></div>
            <div><Label htmlFor="sort-direction">Direction</Label><Select id="sort-direction" value={params.direction} onChange={(event) => update('direction', event.target.value)}><option value="DESC">Descending</option><option value="ASC">Ascending</option></Select></div>
          </div>
        </details>
      </div>
    </div>
    {query.isPending ? <Spinner label="Loading applications" /> : query.error ? <div className="pt-6"><ErrorPanel message={query.error.message} onRetry={() => void query.refetch()} /></div> : query.data.items.length === 0 ? <section className="grid min-h-[clamp(300px,calc(100dvh-27rem),430px)] place-items-center px-4 py-10 text-center" aria-labelledby="applications-empty-title"><div><div className="mx-auto mb-5 grid h-20 w-20 place-items-center rounded-full border border-[#786a5d]/20 bg-white/55 shadow-[0_8px_28px_rgba(91,58,36,.05)]"><BriefcaseBusiness className="text-[#5f5f5d]" size={29} strokeWidth={1.45} aria-hidden="true" /></div><h2 id="applications-empty-title" className="text-[26px] font-semibold tracking-[-0.035em]">{hasFilters ? 'No matches found' : 'No applications yet'}</h2><p className="mt-3 text-[16px] text-muted-foreground">{hasFilters ? 'Try clearing one or more filters.' : 'Add your first opportunity to start your pipeline.'}</p>{hasFilters ? <Button className="mt-8 border bg-white/70 text-foreground shadow-xs hover:bg-white" onClick={() => setSearch(new URLSearchParams())}>Clear filters</Button> : <Link to="/applications/new" className={`${primaryLinkClass} mt-8 min-w-[178px]`}>Add application</Link>}</div></section> : <><div className="space-y-3 pt-6">{query.data.items.map((item) => <ApplicationRow key={item.id} item={item} />)}</div><div className="mt-5 flex flex-wrap items-center justify-end gap-2 text-sm text-muted-foreground"><Button className="border bg-white/70 text-foreground shadow-xs hover:bg-white" disabled={query.data.page === 0} onClick={() => update('page', String(query.data.page - 1))}>Previous</Button><span>Page {query.data.page + 1} of {query.data.totalPages}</span><Button className="border bg-white/70 text-foreground shadow-xs hover:bg-white" disabled={query.data.page + 1 >= query.data.totalPages} onClick={() => update('page', String(query.data.page + 1))}>Next</Button></div></>}
  </>
}

function useRouteApplication() {
  const rawId = useParams().id ?? '', id = Number(rawId)
  return { id, valid: /^\d+$/.test(rawId) && id > 0, query: useApplication(id) }
}

export function EditApplicationPage() {
  const { id, valid, query } = useRouteApplication(), navigate = useNavigate(), mutation = useUpdateApplication()
  if (!valid) return <Navigate to="/not-found" replace />
  if (query.isPending) return <Spinner label="Loading application" />
  if (query.error) return <ErrorPanel title={query.error instanceof ApiProblem && query.error.status === 404 ? 'Application not found' : undefined} message={query.error.message} onRetry={() => void query.refetch()} />
  return <><PageHeader eyebrow="Keep it current" title="Edit application" description="Update role details without rewriting its status history." /><ApplicationForm detail={query.data} submitLabel="Save changes" pending={mutation.isPending} serverError={mutation.error} onSubmit={(request) => { const { status: _status, ...update } = request; mutation.mutate({ id, request: update }, { onSuccess: () => void navigate(`/applications/${id}`) }) }} /></>
}

function DetailItem({ label, children }: { label: string; children: React.ReactNode }) { return <div><dt className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</dt><dd className="mt-1 text-sm">{children || 'Not provided'}</dd></div> }

export function ApplicationDetailPage() {
  const { id, valid, query } = useRouteApplication(), navigate = useNavigate(), remove = useDeleteApplication(), [confirmDelete, setConfirmDelete] = useState(false)
  if (!valid) return <Navigate to="/not-found" replace />
  if (query.isPending) return <Spinner label="Loading application" />
  if (query.error) return <><Link to="/applications" className="mb-4 inline-flex items-center gap-2 text-sm text-primary"><ArrowLeft size={16} /> Applications</Link><ErrorPanel title={query.error instanceof ApiProblem && query.error.status === 404 ? 'Application not found' : undefined} message={query.error.message} onRetry={() => void query.refetch()} /></>
  const item = query.data
  return <><Link to="/applications" className="mb-5 inline-flex items-center gap-2 text-sm font-medium text-primary"><ArrowLeft size={16} /> Applications</Link><PageHeader eyebrow={item.company.name} title={item.positionTitle} description={`Last updated ${formatInstant(item.updatedAt)}`} action={<div className="flex gap-2"><Link to={`/applications/${id}/edit`} className={secondaryLinkClass}><Pencil size={16} /> Edit</Link><Button className="bg-destructive text-white" onClick={() => setConfirmDelete(true)}><Trash2 size={16} /> Delete</Button></div>} />
    {confirmDelete && <Card className="mb-5 border-[#bdbdbb] bg-[#f0f0ef] p-5" role="alertdialog" aria-labelledby="delete-title"><h2 id="delete-title" className="font-semibold">Delete this application?</h2><p className="mt-1 text-sm text-muted-foreground">This permanently removes the application and its status history.</p>{remove.error && <p role="alert" className="mt-2 text-sm text-foreground">{remove.error.message}</p>}<div className="mt-4 flex gap-2"><Button className="bg-destructive text-white" disabled={remove.isPending} onClick={() => remove.mutate(id, { onSuccess: () => void navigate('/applications') })}>{remove.isPending ? 'Deleting…' : 'Delete permanently'}</Button><Button className="border bg-white text-foreground" onClick={() => setConfirmDelete(false)}>Cancel</Button></div></Card>}
    <div className="grid gap-5 lg:grid-cols-[1.4fr_.8fr]"><div className="space-y-5"><Card className="p-5 sm:p-6"><div className="mb-5 flex items-center justify-between gap-3"><Badge tone={statusTone(item.status)}>{STATUS_LABELS[item.status]}</Badge>{item.jobUrl && <a href={item.jobUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1 text-sm font-medium text-primary">Open job post <ExternalLink size={15} /></a>}</div><dl className="grid gap-5 sm:grid-cols-2"><DetailItem label="Applied date">{formatDate(item.appliedDate)}</DetailItem><DetailItem label="Source">{item.source.name}</DetailItem><DetailItem label="Work mode">{item.workMode.toLowerCase()}</DetailItem><DetailItem label="Location">{item.location}</DetailItem><DetailItem label="Compensation">{item.salaryMin == null && item.salaryMax == null ? 'Not provided' : `${item.currency} ${item.salaryMin ?? '-'} - ${item.salaryMax ?? '-'} ${item.salaryPeriod?.toLowerCase()}`}</DetailItem><DetailItem label="Technologies">{item.technologies.map(({ name }) => name).join(', ') || 'Not provided'}</DetailItem></dl>{item.notes && <div className="mt-6 border-t pt-5"><p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Notes</p><p className="mt-2 whitespace-pre-wrap text-sm leading-6">{item.notes}</p></div>}</Card><Card className="p-5 sm:p-6"><h2 className="text-lg font-semibold">Change status</h2><p className="mb-4 text-sm text-muted-foreground">Updates are recorded in the timeline after the server confirms them.</p><StatusControl id={id} status={item.status} appliedDate={item.appliedDate} /></Card></div><Card className="p-5 sm:p-6"><h2 className="text-lg font-semibold">Status history</h2><ol className="mt-5 space-y-4">{item.history.length ? item.history.map((entry) => <li key={entry.id} className="relative border-l-2 border-primary/20 pl-4"><span className="absolute -left-[5px] top-1 h-2 w-2 rounded-full bg-primary" /><p className="text-sm font-medium">{STATUS_LABELS[entry.status]}</p><time className="text-xs text-muted-foreground">{formatInstant(entry.changedAt)}</time></li>) : <li className="text-sm text-muted-foreground">No history was returned.</li>}</ol></Card></div>
  </>
}

const TRACKER_ICONS: Record<Exclude<TrackerGroup, 'closed'>, typeof Bookmark> = {
  bookmarked: Bookmark,
  applied: Send,
  interview: Users,
  offer: BadgeCheck,
}

function TrackerCard({ item }: { item: JobApplicationSummary }) {
  return <Card className="bg-white/65 p-3.5 shadow-[0_7px_20px_rgba(91,58,36,.05)] transition hover:-translate-y-0.5 hover:bg-white/80 hover:shadow-[0_10px_24px_rgba(91,58,36,.08)]">
    <Link to={`/applications/${item.id}`} className="block rounded-[9px] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2">
      <p className="font-semibold leading-snug">{item.company.name}</p>
      <p className="mt-1 text-sm text-muted-foreground">{item.positionTitle}</p>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
        <Badge tone={statusTone(item.status)}>{STATUS_LABELS[item.status]}</Badge>
        <span className="text-xs text-muted-foreground">{formatDate(item.appliedDate)}</span>
      </div>
    </Link>
    <div className="mt-3 border-t border-[#786a5d]/15 pt-3"><StatusControl compact id={item.id} status={item.status} appliedDate={item.appliedDate} /></div>
  </Card>
}

function TrackerColumn({ group, label, items }: { group: Exclude<TrackerGroup, 'closed'>; label: string; items: JobApplicationSummary[] }) {
  const Icon = TRACKER_ICONS[group]
  return <section className="flex min-h-[360px] min-w-0 flex-col rounded-[16px] border border-[#786a5d]/15 bg-white/25 p-3" aria-labelledby={`group-${group}`}>
    <div className="mb-3 flex items-center gap-2 px-1">
      <Icon size={18} strokeWidth={1.6} aria-hidden="true" />
      <h2 id={`group-${group}`} className="font-semibold">{label}</h2>
      <span className="ml-auto grid min-w-7 place-items-center rounded-[8px] bg-white/55 px-2 py-1 text-xs font-semibold text-muted-foreground">{items.length}</span>
    </div>
    <div className="space-y-3">{items.map((item) => <TrackerCard key={item.id} item={item} />)}</div>
    {items.length === 0 ? <p className="px-1 py-8 text-center text-sm text-muted-foreground">No applications in this stage.</p> : null}
    <Link to="/applications/new" className="mt-auto flex min-h-11 items-center justify-center gap-2 rounded-[10px] px-3 pt-3 text-sm font-medium text-foreground transition hover:bg-white/45 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><Plus size={15} aria-hidden="true" /> Add application</Link>
  </section>
}

export function TrackerPage() {
  const query = useTracker()
  const groups = useMemo(() => groupApplications(query.data ?? []), [query.data])
  return <><PageHeader title="Tracker" description="Track your job applications across every stage." action={<Link to="/applications/new" className={primaryLinkClass}><Plus size={17} /> Add application</Link>} />
    {query.isPending ? <Spinner label="Loading the complete tracker" /> : query.error ? <ErrorPanel title="Tracker could not be loaded" message={query.error.message} onRetry={() => void query.refetch()} /> : query.data.length === 0 ? <Card className="grid min-h-72 place-items-center bg-white/45 p-8 text-center"><div><h2 className="text-xl font-semibold">Your tracker is empty</h2><p className="mt-2 text-sm text-muted-foreground">Add an application to begin.</p><Link to="/applications/new" className={`${primaryLinkClass} mt-4`}>Add application</Link></div></Card> : <div className="space-y-5">
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">{ACTIVE_TRACKER_GROUPS.map((group) => <TrackerColumn key={group.id} group={group.id} label={group.label} items={groups[group.id]} />)}</div>
      <section className="rounded-[16px] border border-[#786a5d]/15 bg-white/25 p-4" aria-labelledby="group-closed">
        <div className="mb-4 flex items-center gap-2">
          <Archive size={18} strokeWidth={1.6} aria-hidden="true" />
          <h2 id="group-closed" className="font-semibold">Closed</h2>
          <span className="grid min-w-7 place-items-center rounded-[8px] bg-white/55 px-2 py-1 text-xs font-semibold text-muted-foreground">{groups.closed.length}</span>
          <p className="ml-auto hidden text-sm text-muted-foreground sm:block">Rejected and withdrawn applications remain available here.</p>
        </div>
        {groups.closed.length === 0 ? <p className="rounded-[12px] border border-dashed border-[#786a5d]/20 px-4 py-6 text-center text-sm text-muted-foreground">No closed applications.</p> : <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">{groups.closed.map((item) => <TrackerCard key={item.id} item={item} />)}</div>}
      </section>
    </div>}
  </>
}

export function NotFoundPage() { return <Card className="grid min-h-80 place-items-center p-8 text-center"><div><p className="text-sm font-semibold text-primary">404</p><h1 className="mt-2 text-3xl font-semibold">Page not found</h1><p className="mt-2 text-muted-foreground">The page you requested does not exist.</p><Link to="/applications" className={`${primaryLinkClass} mt-5`}>Back to applications</Link></div></Card> }
