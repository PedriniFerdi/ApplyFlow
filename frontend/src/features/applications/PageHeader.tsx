import type { ReactNode } from 'react'

export function PageHeader({ eyebrow, title, description, action, className }: { eyebrow?: string; title: string; description: string; action?: ReactNode; className?: string }) {
  return <div className={`${className ?? 'mb-8'} flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between`}><div>{eyebrow && <p className="mb-2 text-sm font-medium text-muted-foreground">{eyebrow}</p>}<h1 className="font-heading text-[40px] font-semibold leading-none tracking-[-0.05em] sm:text-[48px]">{title}</h1><p className="mt-3 max-w-2xl text-[16px] leading-6 text-muted-foreground">{description}</p></div>{action}</div>
}
