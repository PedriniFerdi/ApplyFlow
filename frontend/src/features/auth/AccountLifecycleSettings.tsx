import { useMutation } from '@tanstack/react-query'
import { useId, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Button, Input, Label } from '@/components/ui'
import { ApiProblem } from '@/lib/api-client'
import { accountExportUrl, confirmAccountDeletion, requestAccountDeletion } from './api'
import { useAuth } from './auth-context'

export function AccountLifecycleSettings() {
  const { completeAccountDeletion } = useAuth()
  const navigate = useNavigate()
  const id = useId()
  const busy = useRef(false)
  const [token, setToken] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const request = useMutation({ mutationFn: requestAccountDeletion, retry: false })
  const deletion = useMutation({
    mutationFn: confirmAccountDeletion,
    retry: false,
    onSuccess: async () => {
      await completeAccountDeletion()
      navigate('/sign-in', { replace: true })
    },
  })
  const pending = request.isPending || deletion.isPending
  const valid = /^[A-Za-z0-9_-]{43}$/.test(token) && confirmation === 'DELETE'
  const error = deletion.error ?? request.error

  function requestCode() {
    if (busy.current) return
    busy.current = true
    deletion.reset()
    request.mutate(undefined, { onSettled: () => { busy.current = false } })
  }

  function confirm(event: FormEvent) {
    event.preventDefault()
    if (busy.current || !valid) return
    busy.current = true
    request.reset()
    deletion.mutate({ token, confirmation }, { onSettled: () => { busy.current = false } })
  }

  return <section aria-labelledby={`${id}-title`} className="mt-8 space-y-6 rounded-[15px] border p-6 sm:p-8">
    <div>
      <h2 id={`${id}-title`} className="text-lg font-semibold">Your data and account</h2>
      <p className="mt-2 text-sm leading-6 text-muted-foreground">Download your profile, applications, history and personal catalogs before deleting your account.</p>
      <a href={accountExportUrl} download="applyflow-account-export.json" target="_blank" rel="noopener noreferrer"
        className="mt-4 inline-flex min-h-11 items-center rounded-lg border px-4 text-sm font-semibold underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">Download account JSON</a>
      <p className="mt-2 text-xs leading-5 text-muted-foreground">Your browser handles the download. Check its completion or any error in the new tab. Keep only valid JSON with complete: true, and store it privately. No import or restore is available.</p>
    </div>
    <details className="rounded-lg border p-4 text-sm leading-6">
      <summary className="cursor-pointer font-semibold">Account lifecycle policy</summary>
      <p className="mt-3">Password and Google-only accounts use the same mailbox confirmation. It is not Google reauthentication or MFA, and does not create a password. Password reset closes all sessions; changing a password while signed in closes other sessions.</p>
      <p className="mt-3">Deletion removes your account, owned applications/history, personal companies/technologies, tokens, queued email and stored sessions. Other users’ data and shared catalogs stay. Your Google account, sent or in-flight email, exports, backups, logs and external copies are not erased by this action; their own retention applies. An export already running may finish.</p>
      <p className="mt-3">This beta does not offer email changes, device inventory, data import or deleted-account restoration. Request acceptance is not proof of email delivery.</p>
    </details>
    <div>
      <h3 className="font-semibold">Permanently delete account</h3>
      <p id={`${id}-warning`} className="mt-2 text-sm leading-6">This cannot be undone. You will lose your account and owned data and be signed out everywhere. Download and verify your export first if you want a copy.</p>
      <Button className="mt-4" onClick={requestCode} disabled={pending}>{request.isPending ? 'Requesting code…' : 'Request deletion code'}</Button>
      {request.isSuccess ? <p role="status" className="mt-3 text-sm">{request.data.message} Queue acceptance does not guarantee delivery.</p> : null}
    </div>
    <form onSubmit={confirm} aria-describedby={`${id}-warning`} aria-busy={pending} className="space-y-4">
      <div>
        <Label htmlFor={`${id}-code`}>Deletion confirmation code</Label>
        <Input id={`${id}-code`} value={token} onChange={(event) => setToken(event.target.value)} maxLength={43}
          pattern={'[A-Za-z0-9_\\-]{43}'} autoComplete="off" autoCapitalize="none" spellCheck={false} required disabled={pending} aria-describedby={`${id}-code-help`} />
        <p id={`${id}-code-help`} className="mt-2 text-xs text-muted-foreground">Paste the 43-character email code. Codes expire; requesting one alone deletes nothing.</p>
      </div>
      <div>
        <Label htmlFor={`${id}-confirm`}>Type DELETE to confirm</Label>
        <Input id={`${id}-confirm`} value={confirmation} onChange={(event) => setConfirmation(event.target.value)}
          maxLength={6} pattern="DELETE" autoComplete="off" spellCheck={false} required disabled={pending} />
      </div>
      {error ? <p role="alert" className="text-sm leading-6">{error instanceof ApiProblem ? error.message : 'The request could not be completed.'} No automatic retry was made. If the connection was lost after confirming, sign in again to check the outcome.</p> : null}
      {pending ? <p role="status" className="text-sm">{deletion.isPending ? 'Deleting account…' : 'Requesting confirmation…'}</p> : null}
      <Button type="submit" disabled={pending || !valid} className="bg-destructive text-white hover:bg-destructive/90">Permanently delete my account</Button>
    </form>
  </section>
}
