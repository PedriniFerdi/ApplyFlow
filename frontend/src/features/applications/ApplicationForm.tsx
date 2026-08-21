import { zodResolver } from '@hookform/resolvers/zod'
import { AlertTriangle, CheckCircle2, ChevronDown, Link2, Plus, RefreshCw, X } from 'lucide-react'
import { useEffect, useRef, useState, type ChangeEvent, type ClipboardEvent, type KeyboardEvent } from 'react'
import { useForm } from 'react-hook-form'
import { ApiProblem } from '@/lib/api-client'
import { Button, Card, ErrorPanel, FieldError, Input, Label, Select, Textarea } from '@/components/ui'
import { APPLICATION_STATUSES, type CreateApplicationRequest, type JobApplicationDetail, type JobOfferExtraction } from '@/types/api'
import { STATUS_LABELS } from './model'
import { extractJobOffer, useCompanies, useCreateTechnology, useSources, useTechnologies } from './api'
import { applicationFormDefaults, applicationFormSchema, localDateValue, toApplicationRequest, type ApplicationFormValues } from './form-model'

interface ApplicationFormProps {
  detail?: JobApplicationDetail
  submitLabel: string
  pending: boolean
  serverError?: unknown
  enableJobUrlImport?: boolean
  onSubmit: (request: CreateApplicationRequest) => void
}

type ExtractionState = {
  status: 'idle' | 'loading' | 'success' | 'partial' | 'unsupported' | 'error'
  message?: string
  url?: string
}

const advancedFieldNames: Array<keyof ApplicationFormValues> = [
  'newCompanyWebsite', 'newCompanyIndustry', 'salaryMin', 'salaryMax', 'currency', 'salaryPeriod', 'technologyIds', 'notes',
]

export function ApplicationForm({ detail, submitLabel, pending, serverError, enableJobUrlImport = false, onSubmit }: ApplicationFormProps) {
  const [companySearch, setCompanySearch] = useState(detail?.company.name ?? '')
  const [debouncedCompanySearch, setDebouncedCompanySearch] = useState('')
  const [companyOptionsOpen, setCompanyOptionsOpen] = useState(false)
  const [activeCompanyIndex, setActiveCompanyIndex] = useState(-1)
  const companies = useCompanies(debouncedCompanySearch), sources = useSources(), technologies = useTechnologies(), createTech = useCreateTechnology()
  const [newTechnology, setNewTechnology] = useState('')
  const { register, handleSubmit, watch, setValue, setError, clearErrors, getValues, getFieldState, formState: { errors } } = useForm<ApplicationFormValues>({ resolver: zodResolver(applicationFormSchema), defaultValues: applicationFormDefaults(detail) })
  const companyMode = watch('companyMode')
  const selectedTechnologies = watch('technologyIds')
  const extractionRequest = useRef<{ sequence: number; url: string; controller: AbortController } | null>(null)
  const extractionSequence = useRef(0)
  const advancedDetails = useRef<HTMLDetailsElement>(null)
  const [extractionState, setExtractionState] = useState<ExtractionState>({ status: 'idle' })
  const jobUrlRegistration = register('jobUrl')
  const advancedErrorCount = advancedFieldNames.reduce((count, field) => count + Number(Boolean(errors[field])), 0)

  useEffect(() => {
    if (!(serverError instanceof ApiProblem)) return
    for (const [field, message] of Object.entries(serverError.errors)) {
      if (field in applicationFormDefaults()) setError(field as keyof ApplicationFormValues, { message })
    }
  }, [serverError, setError])

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedCompanySearch(companySearch.trim()), 250)
    return () => window.clearTimeout(timer)
  }, [companySearch])

  useEffect(() => () => extractionRequest.current?.controller.abort(), [])

  useEffect(() => {
    if (advancedErrorCount > 0 && advancedDetails.current) advancedDetails.current.open = true
  }, [advancedErrorCount])

  function submit(values: ApplicationFormValues) {
    onSubmit(toApplicationRequest(values))
  }

  async function addTechnology() {
    const name = newTechnology.trim()
    if (!name || createTech.isPending) return
    try {
      const item = await createTech.mutateAsync(name)
      setValue('technologyIds', [...new Set([...selectedTechnologies, String(item.id)])], { shouldDirty: true })
      setNewTechnology('')
    } catch {
      // The mutation error is rendered next to the technology control.
    }
  }

  function canAutofill(field: keyof ApplicationFormValues) {
    const state = getFieldState(field)
    const value = getValues(field)
    const empty = typeof value === 'string' ? value.trim() === '' : Array.isArray(value) ? value.length === 0 : value == null
    return !state.isDirty && !state.isTouched && empty
  }

  function applyExtraction(result: JobOfferExtraction) {
    if (result.positionTitle && canAutofill('positionTitle')) setValue('positionTitle', result.positionTitle)
    if (result.sourceId && canAutofill('sourceId')) setValue('sourceId', String(result.sourceId))
    if (result.location && canAutofill('location')) setValue('location', result.location)
    if (result.workMode && canAutofill('workMode')) setValue('workMode', result.workMode)
    if (result.salary) {
      if (result.salary.min && canAutofill('salaryMin')) setValue('salaryMin', result.salary.min)
      if (result.salary.max && canAutofill('salaryMax')) setValue('salaryMax', result.salary.max)
      if (canAutofill('currency')) setValue('currency', result.salary.currency)
      if (canAutofill('salaryPeriod')) setValue('salaryPeriod', result.salary.period)
    }
    const companyFieldsUntouched = ['companyMode', 'companyId', 'newCompanyName', 'newCompanyWebsite']
      .every((field) => {
        const state = getFieldState(field as keyof ApplicationFormValues)
        return !state.isDirty && !state.isTouched
      })
    if (result.company?.name && companyFieldsUntouched && !getValues('companyId') && !getValues('newCompanyName')) {
      if (result.company.existingCompanyId) {
        setValue('companyMode', 'existing')
        setValue('companyId', String(result.company.existingCompanyId))
        setCompanySearch(result.company.name)
      } else {
        setValue('companyMode', 'new')
        setValue('newCompanyName', result.company.name)
        setCompanySearch(result.company.name)
        if (result.company.website && canAutofill('newCompanyWebsite')) setValue('newCompanyWebsite', result.company.website)
      }
    }
  }

  function enterCompanyName(value: string) {
    setCompanySearch(value)
    setValue('companyMode', 'new', { shouldDirty: true })
    setValue('companyId', '', { shouldDirty: true })
    setValue('newCompanyName', value, { shouldDirty: true, shouldValidate: true })
    clearErrors('companyId')
    setActiveCompanyIndex(-1)
    setCompanyOptionsOpen(true)
  }

  function selectCompany(company: NonNullable<typeof companies.data>[number]) {
    setCompanySearch(company.name)
    setValue('companyMode', 'existing', { shouldDirty: true })
    setValue('companyId', String(company.id), { shouldDirty: true, shouldValidate: true })
    setValue('newCompanyName', '')
    clearErrors(['companyId', 'newCompanyName'])
    setActiveCompanyIndex(-1)
    setCompanyOptionsOpen(false)
  }

  function navigateCompanyOptions(event: KeyboardEvent<HTMLInputElement>) {
    const options = companies.data ?? []
    if (event.key === 'Escape') {
      setCompanyOptionsOpen(false)
      return
    }
    if (options.length === 0 || !['ArrowDown', 'ArrowUp', 'Enter'].includes(event.key)) return
    if (event.key === 'Enter') {
      if (companyOptionsOpen && activeCompanyIndex >= 0) {
        event.preventDefault()
        selectCompany(options[activeCompanyIndex])
      }
      return
    }
    event.preventDefault()
    setCompanyOptionsOpen(true)
    setActiveCompanyIndex((current) => event.key === 'ArrowDown'
      ? Math.min(current + 1, options.length - 1)
      : current <= 0 ? options.length - 1 : current - 1)
  }

  function invalidateExtraction() {
    extractionRequest.current?.controller.abort()
    extractionRequest.current = null
    extractionSequence.current += 1
    setExtractionState({ status: 'idle' })
  }

  function normalizeJobUrl(value: string) {
    try {
      const url = new URL(value.trim())
      return url.protocol === 'http:' || url.protocol === 'https:' ? url.toString() : null
    } catch {
      return null
    }
  }

  function requestExtraction(rawUrl: string, force = false) {
    if (!enableJobUrlImport) return
    const requestedUrl = normalizeJobUrl(rawUrl)
    if (!requestedUrl) {
      setError('jobUrl', { message: 'Use an HTTP or HTTPS URL' })
      return
    }
    clearErrors('jobUrl')
    if (!force && extractionRequest.current?.url === requestedUrl) return

    extractionRequest.current?.controller.abort()
    const controller = new AbortController()
    const sequence = ++extractionSequence.current
    extractionRequest.current = { sequence, url: requestedUrl, controller }
    setExtractionState({ status: 'loading', url: requestedUrl })

    void extractJobOffer(requestedUrl, controller.signal).then((result) => {
      if (extractionRequest.current?.sequence !== sequence || controller.signal.aborted || normalizeJobUrl(getValues('jobUrl')) !== requestedUrl) return
      const hasSuggestions = Boolean(result.positionTitle || result.company?.name || result.sourceId || result.location || result.workMode || result.salary)
      const hasCoreIdentity = Boolean(result.company?.name && result.positionTitle)
      applyExtraction(result)
      if (!hasSuggestions) {
        extractionRequest.current = null
        setExtractionState({ status: 'unsupported', url: requestedUrl, message: result.warnings.join(' ') || 'No supported job details were found on this page. Continue manually or try again.' })
      } else if (!hasCoreIdentity) {
        setExtractionState({ status: 'partial', url: requestedUrl, message: result.warnings.join(' ') || 'Company or position title was not available. Review the imported details before saving.' })
      } else {
        setExtractionState({ status: 'success', url: requestedUrl, message: 'Review the imported details before saving.' })
      }
    }).catch((error: unknown) => {
      if (controller.signal.aborted || extractionRequest.current?.sequence !== sequence || normalizeJobUrl(getValues('jobUrl')) !== requestedUrl) return
      extractionRequest.current = null
      setExtractionState({
        status: error instanceof ApiProblem && error.status === 422 ? 'unsupported' : 'error',
        url: requestedUrl,
        message: error instanceof Error ? error.message : 'Job details could not be imported. Continue manually.',
      })
    })
  }

  function changeJobUrl(event: ChangeEvent<HTMLInputElement>) {
    invalidateExtraction()
    void jobUrlRegistration.onChange(event)
  }

  function importPastedUrl(event: ClipboardEvent<HTMLInputElement>) {
    if (!enableJobUrlImport) return
    const requestedUrl = normalizeJobUrl(event.clipboardData.getData('text'))
    if (!requestedUrl) return
    event.preventDefault()
    setValue('jobUrl', requestedUrl, { shouldDirty: true, shouldTouch: true, shouldValidate: true })
    requestExtraction(requestedUrl)
  }

  const problemMessage = serverError instanceof Error ? serverError.message : undefined
  const catalogProblem = companies.error || sources.error || technologies.error

  const companyFields = <div className="relative">
    <Label htmlFor="companyName">Company name</Label>
    <Input
      id="companyName"
      role="combobox"
      aria-autocomplete="list"
      aria-controls="company-options"
      aria-expanded={companyOptionsOpen && Boolean(companies.data?.length)}
      aria-activedescendant={activeCompanyIndex >= 0 ? `company-option-${companies.data?.[activeCompanyIndex]?.id}` : undefined}
      value={companySearch}
      onChange={(event) => enterCompanyName(event.target.value)}
      onFocus={() => setCompanyOptionsOpen(true)}
      onBlur={() => window.setTimeout(() => setCompanyOptionsOpen(false), 0)}
      onKeyDown={navigateCompanyOptions}
      placeholder="Search or enter a company name"
      autoComplete="off"
    />
    {companyOptionsOpen && companies.data && companies.data.length > 0 ? <div id="company-options" role="listbox" aria-label="Matching companies" className="absolute z-20 mt-1 max-h-52 w-full overflow-y-auto rounded-[9px] border border-[#786a5d]/20 bg-white p-1 shadow-lg">
      {companies.data.map((company, index) => <button
        id={`company-option-${company.id}`}
        key={company.id}
        type="button"
        role="option"
        aria-selected={companyMode === 'existing' && getValues('companyId') === String(company.id)}
        className={index === activeCompanyIndex ? 'flex w-full items-center justify-between rounded-[7px] bg-[#f2f0ed] px-3 py-2 text-left text-sm' : 'flex w-full items-center justify-between rounded-[7px] px-3 py-2 text-left text-sm hover:bg-[#f2f0ed]'}
        onMouseDown={(event) => event.preventDefault()}
        onClick={() => selectCompany(company)}
      ><span>{company.name}</span><span className="ml-3 text-xs text-muted-foreground">{company.website ?? company.companyType.toLowerCase()}</span></button>)}
    </div> : null}
    <p className="mt-1 text-xs text-muted-foreground">{!companySearch ? 'Search existing companies or enter a new name.' : companyMode === 'existing' ? 'Using an existing company.' : 'No match selected; this company will be created when you save.'}</p>
    <FieldError message={companyMode === 'existing' ? errors.companyId?.message : errors.newCompanyName?.message} />
  </div>

  const advancedFields = <div className="grid gap-4 border-t border-[#786a5d]/15 px-4 pb-5 pt-4 sm:px-5 md:grid-cols-2">
    {companyMode === 'new' ? <fieldset className="grid gap-3 md:col-span-2 md:grid-cols-3"><legend className="sr-only">Company details</legend><div><Label htmlFor="newCompanyType">Company type</Label><Select id="newCompanyType" {...register('newCompanyType')}><option value="STARTUP">Startup</option><option value="SCALEUP">Scaleup</option><option value="CORPORATE">Corporate</option><option value="CONSULTING">Consulting</option><option value="OTHER">Other</option></Select></div><div><Label htmlFor="newCompanyWebsite">Company website</Label><Input id="newCompanyWebsite" type="url" {...register('newCompanyWebsite')} /><FieldError message={errors.newCompanyWebsite?.message} /></div><div><Label htmlFor="newCompanyIndustry">Industry</Label><Input id="newCompanyIndustry" {...register('newCompanyIndustry')} /></div></fieldset> : null}
    <fieldset className="md:col-span-2"><legend className="mb-2 text-sm font-medium text-foreground">Compensation</legend><div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4"><div><Label htmlFor="salaryMin">Minimum</Label><Input id="salaryMin" inputMode="decimal" {...register('salaryMin')} /><FieldError message={errors.salaryMin?.message} /></div><div><Label htmlFor="salaryMax">Maximum</Label><Input id="salaryMax" inputMode="decimal" {...register('salaryMax')} /><FieldError message={errors.salaryMax?.message} /></div><div><Label htmlFor="currency">Currency</Label><Input id="currency" maxLength={3} placeholder="USD" {...register('currency')} /><FieldError message={errors.currency?.message} /></div><div><Label htmlFor="salaryPeriod">Period</Label><Select id="salaryPeriod" {...register('salaryPeriod')}><option value="">None</option><option value="YEARLY">Yearly</option><option value="MONTHLY">Monthly</option><option value="HOURLY">Hourly</option></Select><FieldError message={errors.salaryPeriod?.message} /></div></div></fieldset>
    <fieldset className="md:col-span-2"><legend className="mb-2 text-sm font-medium text-foreground">Technologies</legend><div className="flex flex-wrap gap-2">{technologies.data?.map((item) => <label key={item.id} className="flex min-h-9 cursor-pointer items-center gap-2 rounded-[9px] border border-[#786a5d]/20 bg-white/70 px-3 py-1.5 text-sm"><input type="checkbox" value={String(item.id)} {...register('technologyIds')} />{item.name}</label>)}</div><div className="mt-3 flex max-w-md gap-2"><Input aria-label="New technology name" placeholder="Add a technology" value={newTechnology} onChange={(event) => setNewTechnology(event.target.value)} /><Button className="border bg-background text-foreground" onClick={() => void addTechnology()} disabled={!newTechnology.trim() || createTech.isPending}><Plus size={16} /> Add</Button></div>{createTech.error && <p role="alert" className="mt-2 text-sm text-destructive">{createTech.error.message}</p>}</fieldset>
    <div className="md:col-span-2"><Label htmlFor="notes">Notes</Label><Textarea id="notes" className="min-h-20" placeholder="Interview details, follow-up reminders, or context" {...register('notes')} /></div>
  </div>

  if (!enableJobUrlImport) {
    return <form onSubmit={handleSubmit(submit)} className="space-y-6" noValidate>
      {problemMessage && <ErrorPanel title="Application was not saved" message={problemMessage} />}
      {catalogProblem && <ErrorPanel title="Catalogs are unavailable" message={(catalogProblem as Error).message} onRetry={() => { void companies.refetch(); void sources.refetch(); void technologies.refetch() }} />}
      <Card className="p-5 sm:p-6"><h2 className="text-lg font-semibold">Company</h2><p className="mb-5 text-sm text-muted-foreground">Link this application to an existing company or create a new one.</p>
        <div className="mb-4 flex gap-2"><Button className={companyMode === 'existing' ? 'bg-primary text-primary-foreground' : 'border bg-background text-foreground'} onClick={() => setValue('companyMode', 'existing')}>Existing company</Button><Button className={companyMode === 'new' ? 'bg-primary text-primary-foreground' : 'border bg-background text-foreground'} onClick={() => setValue('companyMode', 'new')}>New company</Button></div>
        {companyMode === 'existing' ? <div className="grid gap-3 sm:grid-cols-2"><div><Label htmlFor="companySearch">Search companies</Label><Input id="companySearch" value={companySearch} onChange={(event) => setCompanySearch(event.target.value)} placeholder="Type a company name" /></div><div><Label htmlFor="companyId">Company</Label><Select id="companyId" {...register('companyId')} disabled={companies.isPending}><option value="">Select a company</option>{detail && !companies.data?.some(({ id }) => id === detail.company.id) && <option value={detail.company.id}>{detail.company.name}</option>}{companies.data?.map((company) => <option key={company.id} value={company.id}>{company.name}</option>)}</Select><FieldError message={errors.companyId?.message} /></div></div> : <div className="grid gap-4 sm:grid-cols-2"><div><Label htmlFor="newCompanyName">Company name</Label><Input id="newCompanyName" {...register('newCompanyName')} /><FieldError message={errors.newCompanyName?.message} /></div><div><Label htmlFor="newCompanyType">Company type</Label><Select id="newCompanyType" {...register('newCompanyType')}><option value="STARTUP">Startup</option><option value="SCALEUP">Scaleup</option><option value="CORPORATE">Corporate</option><option value="CONSULTING">Consulting</option><option value="OTHER">Other</option></Select></div><div><Label htmlFor="newCompanyWebsite">Website</Label><Input id="newCompanyWebsite" type="url" {...register('newCompanyWebsite')} /><FieldError message={errors.newCompanyWebsite?.message} /></div><div><Label htmlFor="newCompanyIndustry">Industry</Label><Input id="newCompanyIndustry" {...register('newCompanyIndustry')} /></div></div>}
      </Card>
      <Card className="p-5 sm:p-6"><h2 className="text-lg font-semibold">Role and application</h2><div className="mt-5 grid gap-4 sm:grid-cols-2"><div className="sm:col-span-2"><Label htmlFor="positionTitle">Position title</Label><Input id="positionTitle" {...register('positionTitle')} /><FieldError message={errors.positionTitle?.message} /></div><div><Label htmlFor="sourceId">Source</Label><Select id="sourceId" {...register('sourceId')} disabled={sources.isPending}><option value="">Select a source</option>{sources.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select><FieldError message={errors.sourceId?.message} /></div><div><Label htmlFor="status">Status</Label><Select id="status" {...register('status')} disabled={Boolean(detail)}>{APPLICATION_STATUSES.map((status) => <option key={status} value={status}>{STATUS_LABELS[status]}</option>)}</Select>{detail && <p className="mt-1 text-xs text-muted-foreground">Use Change status from the detail page.</p>}</div><div><Label htmlFor="appliedDate">Applied date</Label><Input id="appliedDate" type="date" max={localDateValue()} {...register('appliedDate')} /><FieldError message={errors.appliedDate?.message} /></div><div><Label htmlFor="jobUrl">Job URL</Label><Input id="jobUrl" type="url" {...jobUrlRegistration} /><FieldError message={errors.jobUrl?.message} /></div><div><Label htmlFor="workMode">Work mode</Label><Select id="workMode" {...register('workMode')}><option value="REMOTE">Remote</option><option value="HYBRID">Hybrid</option><option value="ONSITE">On-site</option></Select></div><div><Label htmlFor="location">Location</Label><Input id="location" {...register('location')} /></div></div></Card>
      <Card className="p-5 sm:p-6"><h2 className="text-lg font-semibold">Compensation</h2><div className="mt-5 grid gap-4 sm:grid-cols-4"><div><Label htmlFor="salaryMin">Minimum</Label><Input id="salaryMin" inputMode="decimal" {...register('salaryMin')} /><FieldError message={errors.salaryMin?.message} /></div><div><Label htmlFor="salaryMax">Maximum</Label><Input id="salaryMax" inputMode="decimal" {...register('salaryMax')} /><FieldError message={errors.salaryMax?.message} /></div><div><Label htmlFor="currency">Currency</Label><Input id="currency" maxLength={3} placeholder="USD" {...register('currency')} /><FieldError message={errors.currency?.message} /></div><div><Label htmlFor="salaryPeriod">Period</Label><Select id="salaryPeriod" {...register('salaryPeriod')}><option value="">None</option><option value="YEARLY">Yearly</option><option value="MONTHLY">Monthly</option><option value="HOURLY">Hourly</option></Select><FieldError message={errors.salaryPeriod?.message} /></div></div></Card>
      <Card className="p-5 sm:p-6"><h2 className="text-lg font-semibold">Technologies</h2><div className="mt-4 flex flex-wrap gap-2">{technologies.data?.map((item) => <label key={item.id} className="flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm"><input type="checkbox" value={String(item.id)} {...register('technologyIds')} />{item.name}</label>)}</div><div className="mt-4 flex max-w-md gap-2"><Input aria-label="New technology name" placeholder="Add a technology" value={newTechnology} onChange={(event) => setNewTechnology(event.target.value)} /><Button className="border bg-background text-foreground" onClick={() => void addTechnology()} disabled={!newTechnology.trim() || createTech.isPending}><Plus size={16} /> Add</Button></div>{createTech.error && <p role="alert" className="mt-2 text-sm text-destructive">{createTech.error.message}</p>}</Card>
      <Card className="p-5 sm:p-6"><Label htmlFor="notes">Notes</Label><Textarea id="notes" {...register('notes')} /></Card>
      <div className="flex items-center justify-end gap-3"><Button className="border bg-background text-foreground" onClick={() => history.back()}><X size={16} /> Cancel</Button><Button type="submit" disabled={pending || Boolean(catalogProblem)}>{pending ? 'Saving...' : submitLabel}</Button></div>
    </form>
  }

  const isImporting = extractionState.status === 'loading'
  const importIssue = extractionState.status === 'unsupported' || extractionState.status === 'error'
  const showImportSummary = extractionState.status === 'success' || extractionState.status === 'partial'

  return <form onSubmit={handleSubmit(submit)} className="space-y-4" noValidate>
    {problemMessage && <ErrorPanel title="Application was not saved" message={problemMessage} />}
    {catalogProblem && <ErrorPanel title="Catalogs are unavailable" message={(catalogProblem as Error).message} onRetry={() => { void companies.refetch(); void sources.refetch(); void technologies.refetch() }} />}

    <Card className="p-4 sm:p-5" aria-labelledby="job-import-title">
      <div className="mb-3"><h2 id="job-import-title" className="text-[15px] font-semibold"><label htmlFor="jobUrl">Paste job posting URL</label></h2><p className="mt-0.5 text-sm text-muted-foreground">We will fill available details without replacing anything you entered.</p></div>
      <div className="grid gap-2.5 sm:grid-cols-[minmax(0,1fr)_auto]">
        <div className="relative"><Link2 className="pointer-events-none absolute left-3 top-3.5 text-muted-foreground" size={16} strokeWidth={1.7} aria-hidden="true" /><Input id="jobUrl" className="pl-9" type="url" autoFocus placeholder="https://company.com/jobs/role" aria-describedby="job-import-status" {...jobUrlRegistration} onChange={changeJobUrl} onPaste={importPastedUrl} /><FieldError message={errors.jobUrl?.message} /></div>
        <Button className="min-w-32 bg-[#171716] px-5 text-white hover:bg-black" onClick={() => requestExtraction(getValues('jobUrl'))} disabled={isImporting}>{isImporting ? 'Importing...' : 'Import job'}</Button>
      </div>
      <div id="job-import-status" aria-live="polite" className="mt-2 min-h-5 text-sm">
        {isImporting ? <span className="text-muted-foreground">Reading the job posting. You can keep editing.</span> : null}
        {importIssue ? <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[#7a4a32]"><span>{extractionState.message}</span><button type="button" className="font-semibold underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => requestExtraction(getValues('jobUrl'))}>Try again</button></span> : null}
      </div>
    </Card>

    <div className="flex items-center gap-3 px-1 text-xs text-muted-foreground" aria-hidden="true"><span className="h-px flex-1 bg-[#786a5d]/15" /><span>or enter manually</span><span className="h-px flex-1 bg-[#786a5d]/15" /></div>

    <Card className="overflow-hidden" aria-labelledby="review-application-title">
      {showImportSummary ? <div className={extractionState.status === 'success' ? 'flex flex-col gap-3 border-b border-[#6f9a73]/25 bg-[#edf5ed] px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-5' : 'flex flex-col gap-3 border-b border-[#b39a67]/25 bg-[#f7f2e6] px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-5'}>
        <div className="flex items-start gap-2.5">{extractionState.status === 'success' ? <CheckCircle2 className="mt-0.5 shrink-0 text-[#3f8b50]" size={19} aria-hidden="true" /> : <AlertTriangle className="mt-0.5 shrink-0 text-[#8a6a32]" size={19} aria-hidden="true" />}<div role="status" aria-live="polite" aria-atomic="true"><p className="text-sm font-semibold">{extractionState.status === 'success' ? 'Fields auto-filled from job posting' : 'Some job details were imported'}</p><p className="mt-0.5 text-xs text-muted-foreground">{extractionState.message}</p></div></div>
        <Button className="min-h-9 self-start border bg-white px-3 text-xs text-foreground shadow-xs hover:bg-white sm:self-auto" onClick={() => requestExtraction(getValues('jobUrl'), true)}><RefreshCw size={14} aria-hidden="true" /> Update from URL</Button>
      </div> : null}

      <div className="px-4 py-4 sm:px-5">
        <div className="mb-4"><h2 id="review-application-title" className="text-lg font-semibold">Review application</h2><p className="mt-0.5 text-sm text-muted-foreground">Check the essentials, then save. You can edit everything later.</p></div>
        <div className="grid gap-x-5 gap-y-3.5 md:grid-cols-2">
          <div className="md:col-span-2">{companyFields}</div>
          <div><Label htmlFor="positionTitle">Position title</Label><Input id="positionTitle" {...register('positionTitle')} /><FieldError message={errors.positionTitle?.message} /></div>
          <div><Label htmlFor="location">Location</Label><Input id="location" placeholder="City, country, or remote" {...register('location')} /></div>
          <div><Label htmlFor="status">Status</Label><Select id="status" {...register('status')}>{APPLICATION_STATUSES.map((status) => <option key={status} value={status}>{STATUS_LABELS[status]}</option>)}</Select></div>
          <div><Label htmlFor="appliedDate">Applied date</Label><Input id="appliedDate" type="date" max={localDateValue()} {...register('appliedDate')} /><FieldError message={errors.appliedDate?.message} /></div>
          <div><Label htmlFor="sourceId">Source</Label><Select id="sourceId" {...register('sourceId')} disabled={sources.isPending}><option value="">Select a source</option>{sources.data?.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select><FieldError message={errors.sourceId?.message} /></div>
          <div><Label htmlFor="workMode">Work mode</Label><Select id="workMode" {...register('workMode')}><option value="REMOTE">Remote</option><option value="HYBRID">Hybrid</option><option value="ONSITE">On-site</option></Select></div>
        </div>
      </div>

      <details ref={advancedDetails} className="group border-t border-[#786a5d]/15">
        <summary className="flex min-h-11 cursor-pointer list-none items-center gap-2 px-4 text-sm font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring sm:px-5">Additional details <span className="hidden font-normal text-muted-foreground sm:inline">Salary, technologies and notes</span>{advancedErrorCount > 0 ? <span className="ml-auto text-right text-xs text-destructive sm:text-sm">{advancedErrorCount} field{advancedErrorCount === 1 ? '' : 's'} need attention</span> : <ChevronDown className="ml-auto shrink-0 transition-transform group-open:rotate-180" size={16} aria-hidden="true" />}</summary>
        {advancedFields}
      </details>

      <div className="flex flex-col-reverse gap-2 border-t border-[#786a5d]/15 bg-white/35 px-4 py-3 sm:flex-row sm:items-center sm:justify-end sm:px-5">
        <Button className="border bg-white text-foreground shadow-xs hover:bg-white" onClick={() => history.back()}><X size={16} /> Cancel</Button>
        <Button type="submit" className="min-w-40 bg-[#171716] text-white hover:bg-black" disabled={pending || Boolean(catalogProblem)}>{pending ? 'Saving...' : submitLabel}</Button>
      </div>
    </Card>
  </form>
}
