import { BarChart3, BriefcaseBusiness, ChevronLeft, KanbanSquare, LogOut, Plus, ShieldCheck } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { NavLink, Outlet } from 'react-router-dom'
import landscape from '@/assets/applyflow-landscape.png'
import { useAuth } from '@/features/auth/auth-context'
import { cn } from '@/lib/utils'
import { loadNewApplicationPage } from '@/route-loaders'

const links = [
  { to: '/applications', label: 'Applications', icon: BriefcaseBusiness, end: true },
  { to: '/tracker', label: 'Tracker', icon: KanbanSquare, end: false },
  { to: '/analytics', label: 'Analytics', icon: BarChart3, end: false },
  { to: '/applications/new', label: 'New application', icon: Plus, end: false, emphasized: true },
]

function Brand() {
  return <NavLink to="/applications" aria-label="ApplyFlow applications" className="font-semibold tracking-[-0.025em] text-foreground">
    <span className="text-[17px]">ApplyFlow</span>
  </NavLink>
}

function SidebarNav() {
  const queryClient = useQueryClient()
  const warmNewApplication = () => {
    void Promise.all([
      loadNewApplicationPage(),
      import('@/features/applications/api').then((api) => api.prefetchNewApplicationResources(queryClient)),
    ])
      .catch(() => { /* Navigation remains available when speculative prefetch fails. */ })
  }
  return <nav aria-label="Primary navigation" className="space-y-2">
    {links.map(({ to, label, icon: Icon, end }) => <NavLink key={to} to={to} end={end} onMouseEnter={to === '/applications/new' ? warmNewApplication : undefined} onFocus={to === '/applications/new' ? warmNewApplication : undefined} className={({ isActive }) => cn('flex min-h-12 items-center gap-3 rounded-[12px] px-4 text-[15px] font-medium text-[#5d5d5d] transition-colors hover:bg-[#ebebec] hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', isActive && 'bg-[#e5e5e7] font-semibold text-foreground')}>
      <Icon size={19} strokeWidth={1.7} aria-hidden="true" />
      <span>{label}</span>
    </NavLink>)}
  </nav>
}

function TopNav() {
  const queryClient = useQueryClient()
  const warmNewApplication = () => {
    void Promise.all([
      loadNewApplicationPage(),
      import('@/features/applications/api').then((api) => api.prefetchNewApplicationResources(queryClient)),
    ])
      .catch(() => { /* Navigation remains available when speculative prefetch fails. */ })
  }
  return <nav aria-label="Page navigation" className="flex min-w-max items-stretch gap-2">
    {links.map(({ to, label, icon: Icon, end, emphasized }) => <NavLink key={to} to={to} end={end} onMouseEnter={emphasized ? warmNewApplication : undefined} onFocus={emphasized ? warmNewApplication : undefined} className={({ isActive }) => cn('relative flex min-h-14 items-center gap-2 px-4 text-[15px] font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', emphasized && 'ml-3 border-l pl-7', isActive && 'font-semibold text-foreground after:absolute after:inset-x-3 after:bottom-0 after:h-px after:bg-foreground')}>
      {emphasized && <Icon size={19} strokeWidth={1.7} aria-hidden="true" />}
      <span>{label}</span>
    </NavLink>)}
  </nav>
}

export function AppShell() {
  const { user, logout } = useAuth()
  return <div className="relative isolate min-h-[100dvh] overflow-hidden bg-[#d88245] p-3 sm:p-5 lg:p-7">
    <img src={landscape} alt="" loading="lazy" decoding="async" fetchPriority="low" className="absolute inset-0 -z-10 h-full w-full object-cover object-center" aria-hidden="true" />
    <div className="absolute inset-0 -z-10 bg-[#2b2118]/[0.06]" aria-hidden="true" />
    <a href="#main-content" className="sr-only rounded-[10px] bg-background p-3 focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:not-sr-only">Skip to content</a>

    <div className="app-shell-glass relative mx-auto min-h-[calc(100dvh-1.5rem)] max-w-[1440px] overflow-hidden rounded-[24px] border border-white/40 shadow-[0_26px_80px_rgba(72,41,24,.22),0_5px_18px_rgba(72,41,24,.12)] sm:min-h-[calc(100dvh-2.5rem)] lg:h-[calc(100dvh-3.5rem)] lg:min-h-[680px]">
      <div className="relative z-10 min-h-[inherit] lg:grid lg:h-full lg:grid-cols-[244px_minmax(0,1fr)] lg:items-stretch">
        <aside className="app-shell-sidebar hidden min-h-0 flex-col border-r border-[#75685d]/15 p-4 lg:flex">
          <div className="flex items-center justify-between gap-3 px-2 pb-1 pt-3">
            <Brand />
            <NavLink to="/applications" aria-label="Go to applications" className="grid h-8 w-8 place-items-center rounded-[9px] border bg-white text-muted-foreground shadow-xs transition hover:text-foreground active:translate-y-px">
              <ChevronLeft size={17} strokeWidth={1.8} aria-hidden="true" />
            </NavLink>
          </div>
          <div className="mt-14"><SidebarNav /></div>
          <div className="mt-auto border-t border-black/[0.07] px-2 pb-3 pt-4">
            <p className="truncate px-2 text-sm font-semibold">{user?.fullName}</p>
            <p className="truncate px-2 text-xs text-muted-foreground">{user?.email}</p>
            <div className="mt-3 grid grid-cols-2 gap-2">
              <NavLink to="/settings/security" className="flex min-h-11 items-center justify-center gap-2 rounded-[10px] border bg-white text-xs font-semibold shadow-xs transition hover:bg-[#e9e9e7] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:translate-y-px"><ShieldCheck size={15} aria-hidden="true" />Security</NavLink>
              <button type="button" onClick={() => void logout()} className="flex min-h-11 items-center justify-center gap-2 rounded-[10px] border bg-white text-xs font-semibold shadow-xs transition hover:bg-[#e9e9e7] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:translate-y-px"><LogOut size={15} aria-hidden="true" />Sign out</button>
            </div>
          </div>
        </aside>

        <section className="app-shell-content min-h-[inherit] min-w-0 lg:flex lg:min-h-0 lg:flex-col lg:overflow-hidden">
          <header className="flex min-h-[76px] items-center justify-between gap-4 border-b border-[#75685d]/15 px-5 sm:px-7 lg:min-h-[88px] lg:justify-end lg:px-10 xl:px-[54px]">
            <div className="lg:hidden"><Brand /></div>
            <div className="min-w-0 flex-1 overflow-x-auto lg:flex-none"><TopNav /></div>
            <div className="flex shrink-0 items-center gap-2 lg:hidden">
              <NavLink to="/settings/security" aria-label="Security settings" className="grid h-11 w-11 place-items-center rounded-[10px] border bg-white text-muted-foreground shadow-xs transition hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:translate-y-px"><ShieldCheck size={17} aria-hidden="true" /></NavLink>
              <button type="button" onClick={() => void logout()} aria-label="Sign out" className="grid h-11 w-11 place-items-center rounded-[10px] border bg-white text-muted-foreground shadow-xs transition hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:translate-y-px"><LogOut size={17} aria-hidden="true" /></button>
            </div>
          </header>
          <main id="main-content" className="px-5 pb-8 pt-7 sm:px-8 lg:min-h-0 lg:flex-1 lg:overflow-y-auto lg:px-10 lg:pb-10 lg:pt-8 xl:px-[54px]"><Outlet /></main>
        </section>
      </div>
    </div>
  </div>
}
