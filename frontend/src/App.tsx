import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, Route, Routes, useLocation } from 'react-router-dom'
import {
  ArrowDownLeft, ArrowRight, ArrowUpRight, Bell, CalendarDays, ChartNoAxesCombined,
  ChevronDown, CircleHelp, ClipboardCheck, CreditCard, FileChartColumnIncreasing,
  Landmark, LayoutDashboard, Menu, Moon, Plus, Search, Settings, ShieldCheck, Sun,
  Users, WalletCards, X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { formatMoney } from './money'

type NavItem = { label: string; path: string; icon: LucideIcon }

const nav: NavItem[] = [
  { label: 'Overview', path: '/', icon: LayoutDashboard },
  { label: 'Members', path: '/members', icon: Users },
  { label: 'Investments', path: '/investments', icon: ChartNoAxesCombined },
  { label: 'Payments', path: '/payments', icon: CreditCard },
  { label: 'Approvals', path: '/approvals', icon: ClipboardCheck },
  { label: 'Reports', path: '/reports', icon: FileChartColumnIncreasing },
  { label: 'Settings', path: '/settings', icon: Settings },
]

const previewMembers = [
  { number: 'CU-001', name: 'Ama Example', initials: 'AE', tone: 'peach', principal: '30000.00', income: '4796.87', equity: '34796.87', ratio: '12.01%' },
  { number: 'CU-002', name: 'Kojo Sample', initials: 'KS', tone: 'blue', principal: '24000.00', income: '3600.00', equity: '27600.00', ratio: '9.53%' },
  { number: 'CU-003', name: 'Esi Demo', initials: 'ED', tone: 'mint', principal: '18000.00', income: '2700.00', equity: '20700.00', ratio: '7.15%' },
  { number: 'CU-004', name: 'Kofi Example', initials: 'KE', tone: 'gold', principal: '15000.00', income: '2250.00', equity: '17250.00', ratio: '5.96%' },
]

function ThemeButton() {
  const [dark, setDark] = useState(() => localStorage.getItem('theme') === 'dark' || (!localStorage.getItem('theme') && matchMedia('(prefers-color-scheme: dark)').matches))
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light'
    localStorage.setItem('theme', dark ? 'dark' : 'light')
  }, [dark])
  return <button className="icon-button" aria-label={`Switch to ${dark ? 'light' : 'dark'} theme`} onClick={() => setDark(!dark)}>{dark ? <Sun size={19} /> : <Moon size={19} />}</button>
}

function Sidebar({ close }: { close: () => void }) {
  return <>
    <div className="brand"><div className="brand-mark"><Landmark size={23} strokeWidth={2.2} /></div><div><strong>Harbour</strong><span>Credit Union</span></div></div>
    <div className="side-label">WORKSPACE</div>
    <nav className="side-nav" aria-label="Main navigation">
      {nav.map(({ label, path, icon: Icon }) => <NavLink key={path} to={path} end={path === '/'} onClick={close} className={({ isActive }) => `side-link${isActive ? ' active' : ''}`}><Icon size={19} strokeWidth={1.9} /><span>{label}</span>{label === 'Approvals' && <span className="nav-dot" />}</NavLink>)}
    </nav>
    <div className="sidebar-bottom"><div className="help-icon"><CircleHelp size={19} /></div><div><strong>Need a hand?</strong><span>View the user guide</span></div><ArrowRight size={17} /></div>
  </>
}

function PageHeader({ title, subtitle, action }: { title: string; subtitle: string; action?: ReactNode }) {
  return <div className="page-heading"><div><div className="eyebrow">HARBOUR CREDIT UNION</div><h1>{title}</h1><p>{subtitle}</p></div>{action}</div>
}

function PreviewBanner() {
  return <div className="preview-banner" role="status"><span className="preview-dot" /><div><strong>Design preview</strong><span>Figures and member names on this screen are sample data. Financial actions are unavailable until the secure backend is complete.</span></div></div>
}

function Overview({ asAt }: { asAt: string }) {
  const cards = [
    { label: 'Total member equity', value: '289666.00', detail: '+ 40,876.00 this year', icon: WalletCards, color: 'navy' },
    { label: 'Total principal', value: '250811.50', detail: 'Across all members', icon: ArrowDownLeft, color: 'teal' },
    { label: 'Income earned', value: '38854.50', detail: '15.49% return on principal', icon: ArrowUpRight, color: 'gold' },
  ]
  return <>
    <PageHeader title="Good morning, Treasurer" subtitle="Here is your union at a glance." action={<button className="primary-button" disabled title="Available after member and ledger workflows are complete"><Plus size={18} /> New contribution</button>} />
    <PreviewBanner />
    <div className="section-heading"><div><h2>Financial overview</h2><p>As at {new Date(`${asAt}T12:00:00`).toLocaleDateString('en-GH', { day: 'numeric', month: 'long', year: 'numeric' })}</p></div><button className="text-button" disabled>View report <ArrowRight size={16} /></button></div>
    <div className="stat-grid">{cards.map(({ label, value, detail, icon: Icon, color }) => <div className={`stat-card ${color}`} key={label}><div className="stat-head"><span>{label}</span><span className="stat-icon"><Icon size={19} /></span></div><strong className="stat-value">{formatMoney(value)}</strong><span className="stat-detail">{detail}</span></div>)}</div>
    <div className="overview-grid">
      <section className="surface activity-card"><div className="card-title-row"><div><h2>Equity over time</h2><p>How member balances are growing</p></div><span className="quiet-tag">This year</span></div><div className="chart-wrap" aria-label="Sample equity trend from January to December"><div className="chart-lines"><span>GH₵ 300k</span><span>GH₵ 200k</span><span>GH₵ 100k</span><span>GH₵ 0</span></div><div className="chart-area"><div className="grid-line l1" /><div className="grid-line l2" /><div className="grid-line l3" /><svg viewBox="0 0 600 230" preserveAspectRatio="none" aria-hidden="true"><defs><linearGradient id="fill" x1="0" x2="0" y1="0" y2="1"><stop stopColor="#69a6a7" stopOpacity=".34" /><stop offset="1" stopColor="#69a6a7" stopOpacity=".02" /></linearGradient></defs><path d="M0 185 C50 181 70 175 105 164 S170 152 210 147 S270 129 315 122 S390 101 430 87 S510 67 600 28 L600 230 L0 230 Z" fill="url(#fill)" /><path d="M0 185 C50 181 70 175 105 164 S170 152 210 147 S270 129 315 122 S390 101 430 87 S510 67 600 28" fill="none" stroke="#407e8c" strokeWidth="4" strokeLinecap="round" /></svg></div></div><div className="chart-months"><span>Jan</span><span>Mar</span><span>May</span><span>Jul</span><span>Sep</span><span>Dec</span></div></section>
      <section className="surface attention-card"><div className="card-title-row"><div><h2>At a glance</h2><p>A few things to keep in view</p></div><span className="attention-symbol"><Bell size={19} /></span></div><div className="attention-item"><span className="attention-icon aqua"><Users size={19} /></span><div><strong>16 active members</strong><span>Across the credit union</span></div><ArrowRight size={17} /></div><div className="attention-item"><span className="attention-icon sand"><Landmark size={19} /></span><div><strong>3 investment cycles</strong><span>2 active, 1 matured</span></div><ArrowRight size={17} /></div><div className="attention-item"><span className="attention-icon blush"><ShieldCheck size={19} /></span><div><strong>2 items need approval</strong><span>Review before they are posted</span></div><ArrowRight size={17} /></div><div className="small-note"><span className="small-note-mark">✦</span><div><strong>Clear records, confident decisions.</strong><span>Every posted movement will be traceable to a ledger entry.</span></div></div></section>
    </div>
    <section className="surface members-card"><div className="card-title-row"><div><h2>Member balances</h2><p>A quick view of your members</p></div><button className="text-button" disabled>View all members <ArrowRight size={16} /></button></div><MemberTable /></section>
  </>
}

function MemberTable({ members = previewMembers }: { members?: typeof previewMembers }) {
  return <><div className="table-scroll"><table><thead><tr><th>Member</th><th>Member no.</th><th className="numeric">Principal</th><th className="numeric">Income</th><th className="numeric">Total equity</th><th className="numeric">Equity ratio</th></tr></thead><tbody>{members.map(member => <tr key={member.number}><td><div className="member-cell"><span className={`avatar ${member.tone}`}>{member.initials}</span><strong>{member.name}</strong></div></td><td className="muted">{member.number}</td><td className="numeric">{formatMoney(member.principal)}</td><td className="numeric">{formatMoney(member.income)}</td><td className="numeric strong">{formatMoney(member.equity)}</td><td className="numeric">{member.ratio}</td></tr>)}</tbody></table></div><div className="mobile-member-list">{members.map(member => <div className="mobile-member" key={member.number}><div className="mobile-member-top"><div className="member-cell"><span className={`avatar ${member.tone}`}>{member.initials}</span><div><strong>{member.name}</strong><small>{member.number}</small></div></div><span className="ratio">{member.ratio}</span></div><div className="mobile-member-bottom"><span>Total equity</span><strong>{formatMoney(member.equity)}</strong></div></div>)}</div></>
}

function Members() {
  const [query, setQuery] = useState('')
  const filtered = previewMembers.filter(member => `${member.name} ${member.number}`.toLowerCase().includes(query.toLowerCase()))
  return <><PageHeader title="Members" subtitle="A clear view of everyone in the union." action={<button className="primary-button" disabled><Plus size={18} /> Add member</button>} /><PreviewBanner /><div className="surface list-card"><div className="list-toolbar"><div><h2>Member directory</h2><p>{filtered.length} sample members</p></div><label className="search-box"><Search size={18} /><input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search members" aria-label="Search members" /></label></div>{filtered.length ? <MemberTable members={filtered} /> : <div className="empty-state"><Users size={28} /><strong>No matching members</strong><span>Try another name or member number.</span></div>}</div></>
}

function SimplePage({ title, subtitle, icon: Icon, heading, description }: { title: string; subtitle: string; icon: LucideIcon; heading: string; description: string }) {
  return <><PageHeader title={title} subtitle={subtitle} /><PreviewBanner /><div className="surface empty-page"><div className="empty-illustration"><Icon size={34} strokeWidth={1.6} /></div><h2>{heading}</h2><p>{description}</p><span className="phase-label">Financial workflows are being implemented</span></div></>
}

function App() {
  const [menuOpen, setMenuOpen] = useState(false)
  const [moreOpen, setMoreOpen] = useState(false)
  const [asAt, setAsAt] = useState(new Date().toISOString().slice(0, 10))
  const location = useLocation()
  useEffect(() => { setMenuOpen(false); setMoreOpen(false) }, [location.pathname])
  const section = nav.find(item => item.path === location.pathname)?.label ?? 'Overview'
  return <div className="app-shell">
    <aside className={`sidebar ${menuOpen ? 'open' : ''}`}><Sidebar close={() => setMenuOpen(false)} /></aside>
    {menuOpen && <button className="scrim" aria-label="Close navigation" onClick={() => setMenuOpen(false)} />}
    <div className="main-area"><header className="topbar"><div className="topbar-left"><button className="icon-button desktop-hidden" aria-label="Open navigation" onClick={() => setMenuOpen(true)}><Menu size={22} /></button><span className="breadcrumb"><span className="breadcrumb-root">Workspace <span>/</span></span><strong>{section}</strong></span></div><div className="topbar-right"><label className="date-control"><CalendarDays size={17} /><span>As at</span><input type="date" value={asAt} max={new Date().toISOString().slice(0, 10)} onChange={event => setAsAt(event.target.value)} aria-label="As at date" /></label><ThemeButton /><button className="icon-button notification-button" aria-label="Notifications"><Bell size={19} /><span /></button><div className="profile"><div className="profile-avatar">TR</div><div><strong>Treasurer</strong><span>Workspace preview</span></div><ChevronDown size={15} /></div></div></header>
      <main className="content"><Routes><Route path="/" element={<Overview asAt={asAt} />} /><Route path="/members" element={<Members />} /><Route path="/investments" element={<SimplePage title="Investments" subtitle="Manage every treasury bill cycle with confidence." icon={Landmark} heading="Investment cycles will appear here" description="Create, preview, approve, and track maturity from one place once the ledger is connected." />} /><Route path="/payments" element={<SimplePage title="Payments" subtitle="Follow contributions from submission to confirmation." icon={CreditCard} heading="Payments will appear here" description="Online payments and receipt verification will be tied to the approval and ledger workflows." />} /><Route path="/approvals" element={<SimplePage title="Approvals" subtitle="A second pair of eyes for money movements." icon={ClipboardCheck} heading="Approval requests will appear here" description="Every money action will show its maker, exact impact, and decision history before posting." />} /><Route path="/reports" element={<SimplePage title="Reports" subtitle="Reliable figures for every point in time." icon={FileChartColumnIncreasing} heading="Reports will appear here" description="The dashboard, allocation statements, member statements, and exports will use the same ledger calculations." />} /><Route path="/settings" element={<SimplePage title="Settings" subtitle="Keep the union’s policies in one place." icon={Settings} heading="Organisation settings will appear here" description="Approval rules, rollover policy, payment methods, and branding will be configurable with an audit trail." />} /></Routes></main>
    </div>
    <nav className="bottom-nav" aria-label="Mobile navigation">{nav.slice(0, 4).map(({ label, path, icon: Icon }) => <NavLink key={path} to={path} end={path === '/'} className={({ isActive }) => `bottom-link${isActive ? ' active' : ''}`}><Icon size={20} /><span>{label}</span></NavLink>)}<button className={`bottom-link${moreOpen ? ' active' : ''}`} onClick={() => setMoreOpen(!moreOpen)}><Menu size={20} /><span>More</span></button></nav>
    {moreOpen && <div className="more-sheet"><div className="sheet-top"><strong>More</strong><button className="icon-button" aria-label="Close more menu" onClick={() => setMoreOpen(false)}><X size={19} /></button></div>{nav.slice(4).map(({ label, path, icon: Icon }) => <NavLink key={path} to={path} className="sheet-link"><Icon size={20} />{label}<ArrowRight size={17} /></NavLink>)}</div>}
  </div>
}

export default App
