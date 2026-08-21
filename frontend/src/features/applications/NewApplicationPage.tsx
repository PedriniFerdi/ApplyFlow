import { useNavigate } from 'react-router-dom'
import type { CreateApplicationRequest } from '@/types/api'
import { useCreateApplication } from './api'
import { ApplicationForm } from './ApplicationForm'

export default function NewApplicationPage() {
  const navigate = useNavigate()
  const mutation = useCreateApplication()

  function submit(request: CreateApplicationRequest) {
    mutation.mutate(request, { onSuccess: (detail) => void navigate(`/applications/${detail.id}`) })
  }

  return <div className="mx-auto max-w-[980px]"><header className="mb-4"><h1 className="font-heading text-[34px] font-semibold leading-none tracking-[-0.045em] sm:text-[40px]">New application</h1><p className="mt-2 text-[15px] text-muted-foreground">Save a job in seconds. Paste a job posting URL and we will fill available details.</p></header><ApplicationForm enableJobUrlImport submitLabel="Save application" pending={mutation.isPending} serverError={mutation.error} onSubmit={submit} /></div>
}
