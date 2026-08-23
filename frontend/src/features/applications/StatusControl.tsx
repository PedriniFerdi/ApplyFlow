import { useState } from 'react'
import { ApiProblem } from '@/lib/api-client'
import { Button, ErrorPanel, Input, Label, Select } from '@/components/ui'
import { APPLICATION_STATUSES, type ApplicationStatus } from '@/types/api'
import { useChangeStatus } from './api'
import { STATUS_LABELS } from './model'

export function StatusControl({ id, status, appliedDate, compact = false }: { id: number; status: ApplicationStatus; appliedDate: string | null; compact?: boolean }) {
  const [selected, setSelected] = useState(status)
  const [date, setDate] = useState(appliedDate ?? '')
  const mutation = useChangeStatus()
  const needsDate = selected !== 'BOOKMARKED' && !appliedDate

  async function save() {
    if (selected === status || (needsDate && !date)) return
    try {
      await mutation.mutateAsync({ id, request: { status: selected, appliedDate: needsDate ? date : undefined } })
    } catch {
      // TanStack Query exposes the error below; keeping it handled avoids a global rejection.
    }
  }

  return <div className={compact ? 'space-y-2' : 'space-y-3 rounded-xl border bg-card p-4'} onClick={(event) => event.stopPropagation()}>
    <div className={compact ? 'space-y-2' : 'flex flex-wrap items-end gap-3'}><div className={compact ? '' : 'min-w-52 flex-1'}><Label htmlFor={`status-${id}`}>Status</Label><Select id={`status-${id}`} value={selected} onChange={(event) => { setSelected(event.target.value as ApplicationStatus); mutation.reset() }} disabled={mutation.isPending}>{APPLICATION_STATUSES.map((item) => <option key={item} value={item}>{STATUS_LABELS[item]}</option>)}</Select></div>
    {needsDate && <div className={compact ? '' : 'min-w-44'}><Label htmlFor={`status-date-${id}`}>Applied date</Label><Input id={`status-date-${id}`} type="date" max={new Date().toISOString().slice(0, 10)} value={date} onChange={(event) => setDate(event.target.value)} /></div>}
    <Button onClick={() => void save()} disabled={mutation.isPending || selected === status || (needsDate && !date)}>{mutation.isPending ? 'Saving…' : 'Update'}</Button></div>
    {mutation.isSuccess && <p role="status" aria-atomic="true" className="text-sm text-muted-foreground">Status changed to {STATUS_LABELS[selected]}.</p>}
    {mutation.error && <ErrorPanel title="Status was not changed" message={mutation.error instanceof ApiProblem ? mutation.error.message : 'The status could not be changed.'} />}
  </div>
}
