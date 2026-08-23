import type { ButtonHTMLAttributes, HTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

export function Button({ className, type = 'button', ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button type={type} className={cn('inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-[11px] bg-primary px-4 text-sm font-semibold text-primary-foreground shadow-[inset_0_1px_0_rgba(255,255,255,.16),0_3px_8px_rgba(0,0,0,.12)] transition-[background-color,color,border-color,transform,box-shadow] duration-200 hover:bg-[#2c2c2c] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 active:translate-y-px disabled:pointer-events-none disabled:opacity-50', className)} {...props} />
}

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn('rounded-[15px] border border-[#786a5d]/15 bg-white/70 text-card-foreground shadow-[0_8px_24px_rgba(91,58,36,.055)]', className)} {...props} />
}

export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'neutral' | 'blue' | 'green' | 'red' | 'amber' }) {
  const tones = { neutral: 'bg-[#eeeeed] text-[#555554]', blue: 'bg-[#e9e9e8] text-[#282827]', green: 'bg-[#dfdfdd] text-[#222221]', red: 'bg-[#d7d7d5] text-[#1d1d1c]', amber: 'bg-[#e5e5e3] text-[#353534]' }
  return <span className={cn('inline-flex rounded-full px-2.5 py-1 text-xs font-semibold', tones[tone])}>{children}</span>
}

export function Label({ children, htmlFor }: { children: ReactNode; htmlFor?: string }) {
  return <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-foreground">{children}</label>
}

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn('min-h-11 w-full rounded-[10px] border bg-background px-3 text-sm shadow-xs outline-none placeholder:text-[#777] focus:border-primary focus:ring-2 focus:ring-primary/10 disabled:bg-muted', className)} {...props} />
}

export function Select({ className, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={cn('min-h-11 w-full rounded-[10px] border bg-background px-3 text-sm shadow-xs outline-none focus:border-primary focus:ring-2 focus:ring-primary/10 disabled:bg-muted', className)} {...props} />
}

export function Textarea({ className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn('min-h-28 w-full rounded-[10px] border bg-background px-3 py-2 text-sm shadow-xs outline-none placeholder:text-[#777] focus:border-primary focus:ring-2 focus:ring-primary/10', className)} {...props} />
}

export function ErrorPanel({ title = 'Something went wrong', message, onRetry }: { title?: string; message: string; onRetry?: () => void }) {
  return <div role="alert" className="rounded-[15px] border border-[#8e7968]/25 bg-[#f8f2eb]/90 p-4 text-foreground"><p className="font-semibold">{title}</p><p className="mt-1 text-sm text-muted-foreground">{message}</p>{onRetry && <Button className="mt-3 border bg-white text-foreground shadow-xs hover:bg-[#eee8e1]" onClick={onRetry}>Try again</Button>}</div>
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <div role="status" className="min-h-64 space-y-3 py-7 text-sm text-muted-foreground"><span className="sr-only">{label}</span>{[0, 1, 2].map((item) => <span key={item} className="block h-20 animate-pulse rounded-[14px] border border-[#786a5d]/15 bg-white/55" />)}</div>
}

export function FieldError({ id, message }: { id: string; message?: string }) {
  return message ? <p id={id} role="status" aria-atomic="true" className="mt-1 text-sm text-destructive">{message}</p> : null
}
