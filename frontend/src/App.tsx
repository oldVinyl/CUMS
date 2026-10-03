import LandingPage from "./LandingPage";
import ResponsiveOverlay from "./ResponsiveOverlay";
import ImportsPage from "./ImportsPage";
import { canSeeRoute } from "./navigation";
import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import {
  ArrowRight, CalendarDays, ChartNoAxesCombined,
  ChevronDown, CircleHelp, ClipboardCheck, CreditCard, FileChartColumnIncreasing,
  Landmark, LayoutDashboard, Menu, Moon, Plus, Search, Settings, ShieldCheck, Sun,
  Users, WalletCards,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { formatMoney } from './money'
import { useToast } from './toast-context'
import AuthPage from './AuthPage'
import { currentProfile, logout } from './auth-api'
import type { Profile } from './auth-api'
import LiveMembers from './LiveMembers'
import LiveInvestments from './LiveInvestments'
import LiveDashboard from './LiveDashboard'
import ReportsPage from './ReportsPage'
import LiveLedger from './LiveLedger'
import LivePayments from './LivePayments'
import MemberPortal from './MemberPortal'
import ReconciliationPage from './ReconciliationPage'
import AcceptInvitation from './AcceptInvitation'
import UserAdministration from './UserAdministration'
import ApprovalsPage from './ApprovalsPage'
import SettingsPage from './SettingsPage'
import AuditPage from './AuditPage'

type NavItem = { label: string; path: string; icon: LucideIcon }

const nav: NavItem[] = [
  { label: 'Overview', path: '/', icon: LayoutDashboard },
  { label: 'Members', path: '/members', icon: Users },
  { label: 'Investments', path: '/investments', icon: ChartNoAxesCombined },
  { label: 'Ledger', path: '/ledger', icon: WalletCards },
  { label: 'Payments', path: '/payments', icon: CreditCard },
  { label: 'My membership', path: '/portal', icon: Users },
  { label: 'Approvals', path: '/approvals', icon: ClipboardCheck },
  { label: 'Reports', path: '/reports', icon: FileChartColumnIncreasing },
  { label: 'Reconciliation', path: '/reconciliation', icon: ClipboardCheck },
  { label: 'Accounts and roles', path: '/accounts', icon: ShieldCheck },
  { label: 'Audit log', path: '/audit', icon: ShieldCheck },
  { label: 'Imports', path: '/imports', icon: FileChartColumnIncreasing },
  { label: 'Settings', path: '/settings', icon: Settings },
]

const previewMembers = [
  { number: 'CU-001', name: 'Ama Example', initials: 'AE', tone: 'peach', principal: '30000.00', income: '4796.87', equity: '34796.87', ratio: '12.01%' },
  { number: 'CU-002', name: 'Kojo Sample', initials: 'KS', tone: 'blue', principal: '24000.00', income: '3600.00', equity: '27600.00', ratio: '9.53%' },
  { number: 'CU-003', name: 'Esi Demo', initials: 'ED', tone: 'mint', principal: '18000.00', income: '2700.00', equity: '20700.00', ratio: '7.15%' },
  { number: 'CU-004', name: 'Kofi Example', initials: 'KE', tone: 'gold', principal: '15000.00', income: '2250.00', equity: '17250.00', ratio: '5.96%' },
]

function ThemeButton({ dark, onToggle }: { dark: boolean; onToggle: () => void }) {
  const showToast = useToast();
  return (
    <button
      className="icon-button theme-toggle"
      aria-label={`Switch to ${dark ? "light" : "dark"} theme`}
      onClick={() => {
        onToggle();
        showToast(`${dark ? "Light" : "Dark"} theme on`, { tone: "success" });
      }}
    >
      {dark ? <Sun size={19} /> : <Moon size={19} />}
    </button>
  );
}

const financePaths = ["/investments", "/ledger", "/approvals", "/reconciliation", "/imports"];
const adminPaths = ["/accounts", "/audit"];
function Sidebar({ close, items }: { close: () => void; items: NavItem[] }) {
  const location = useLocation();
  const [financeOpen, setFinanceOpen] = useState(financePaths.includes(location.pathname));
  const [adminOpen, setAdminOpen] = useState(adminPaths.includes(location.pathname));
  useEffect(() => { if (financePaths.includes(location.pathname)) setFinanceOpen(true); if (adminPaths.includes(location.pathname)) setAdminOpen(true); }, [location.pathname]);
  const renderLink = ({ label, path, icon: Icon }: NavItem) => <NavLink key={path} to={path} end={path === "/"} onClick={close} className={({ isActive }) => `side-link${isActive ? " active" : ""}`}><Icon size={19} /><span>{label}</span></NavLink>;
  return (
    <>
      <div className="brand">
        <div className="brand-mark">
          <Landmark size={23} strokeWidth={2.2} />
        </div>
        <div>
          <strong>Harbour</strong>
          <span>Credit Union</span>
        </div>
      </div>
      <div className="side-label">WORKSPACE</div>
      <nav className="side-nav" aria-label="Main navigation">
        {items.filter(item => !financePaths.includes(item.path) && !adminPaths.includes(item.path)).map(renderLink)}
        {items.some(item => financePaths.includes(item.path)) && <details className="nav-group" open={financeOpen} onToggle={event => setFinanceOpen(event.currentTarget.open)}><summary><Landmark size={19} />Financial operations<ChevronDown size={16} /></summary>{items.filter(item => financePaths.includes(item.path)).map(renderLink)}</details>}
        {items.some(item => adminPaths.includes(item.path)) && <details className="nav-group" open={adminOpen} onToggle={event => setAdminOpen(event.currentTarget.open)}><summary><ShieldCheck size={19} />Administration<ChevronDown size={16} /></summary>{items.filter(item => adminPaths.includes(item.path)).map(renderLink)}</details>}
      </nav>
      <div className="sidebar-bottom">
        <div className="help-icon">
          <CircleHelp size={19} />
        </div>
        <div>
          <strong>Need a hand?</strong>
          <span>View the user guide</span>
        </div>
        <ArrowRight size={17} />
      </div>
    </>
  );
}

function PageHeader({ title, subtitle, action }: { title: string; subtitle: string; action?: ReactNode }) {
  return <div className="page-heading"><div><div className="eyebrow">HARBOUR CREDIT UNION</div><h1>{title}</h1><p>{subtitle}</p></div>{action}</div>
}

function PreviewBanner() {
  return <div className="preview-banner" role="status"><span className="preview-dot" /><div><strong>Design preview</strong><span>This workspace uses sample figures and member names. Financial actions are unavailable until the secure backend is complete.</span></div></div>
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
  const [dark, setDark] = useState(() => localStorage.getItem("theme") === "dark" || (!localStorage.getItem("theme") && matchMedia("(prefers-color-scheme: dark)").matches));
  useEffect(() => { document.documentElement.dataset.theme = dark ? "dark" : "light"; }, [dark]);
  useEffect(() => {
    const media = matchMedia("(prefers-color-scheme: dark)");
    const update = () => { if (!localStorage.getItem("theme")) setDark(media.matches); };
    media.addEventListener?.("change", update);
    return () => media.removeEventListener?.("change", update);
  }, []);
  const toggleTheme = () => { localStorage.setItem("theme", dark ? "light" : "dark"); setDark(!dark); };


  const showToast = useToast();
  const navigate = useNavigate();
  const [profile, setProfile] = useState<Profile | null>(null);
  const visibleNav = nav.filter(item => canSeeRoute(item.path, profile));
  const [menuOpen, setMenuOpen] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const accountMenu = useRef<HTMLDivElement>(null);
  const mainNav = visibleNav.filter(item => !financePaths.includes(item.path) && !adminPaths.includes(item.path));
  const [asAt, setAsAt] = useState(new Date().toISOString().slice(0, 10));
  const updateAsAt = (value: string) => {
    if (!value || value > new Date().toISOString().slice(0, 10)) {
      showToast("Choose a valid as-at date", {
        detail: "The date cannot be empty or in the future.",
        tone: "error",
      });
      return;
    }
    if (value !== asAt) {
      setAsAt(value);
      showToast(profile
        ? "As-at date changed" : "Preview date changed", {
        detail: profile
          ? "Figures are updating for the selected date."
          : "Sample balances do not change with the date yet.",
      });
    }
  };
  const location = useLocation();
  const [initialPath] = useState(() => location.pathname);
  useEffect(() => {
    setMenuOpen(false);
    setMoreOpen(false); setProfileOpen(false);
  }, [location.pathname]);
  useEffect(() => {
    if (initialPath !== "/sign-in")
      void currentProfile()
        .then(setProfile)
        .catch(() => {});
  }, [initialPath]);
  useEffect(() => {
    if (!profile?.userId) return;
    let active = true;
    const updateProfile = () => { void currentProfile().then(value => { if (active) setProfile(value); }).catch(() => { /* Keep the current session during a network interruption. */ }); };
    const interval = window.setInterval(updateProfile, 60000);
    window.addEventListener('focus', updateProfile);
    return () => { active = false; window.clearInterval(interval); window.removeEventListener('focus', updateProfile); };
  }, [profile?.userId]);
  useEffect(() => {
    if (!profileOpen) return;
    const close = (event: PointerEvent) => { if (!accountMenu.current?.contains(event.target as Node)) setProfileOpen(false); };
    const escape = (event: KeyboardEvent) => { if (event.key === "Escape") { setProfileOpen(false); accountMenu.current?.querySelector<HTMLButtonElement>("button")?.focus(); } };
    document.addEventListener("pointerdown", close); document.addEventListener("keydown", escape);
    return () => { document.removeEventListener("pointerdown", close); document.removeEventListener("keydown", escape); };
  }, [profileOpen]);
  const signedIn = async () => {
    const confirmed = await currentProfile();
    if (!confirmed)
      throw new Error(
        "Your session could not be confirmed. Please sign in again.",
      );
    setProfile(confirmed);
    navigate("/", { replace: true });
  };
  const signOut = async (allDevices = false) => {
    try {
      await logout(allDevices);
      setProfile(null); setProfileOpen(false); navigate("/", { replace: true });
      showToast(allDevices ? "Signed out on all devices" : "Signed out", {
        tone: "success",
      });
    } catch (error) {
      showToast(error instanceof Error ? error.message : "Could not sign out", {
        tone: "error",
      });
    }
  };
  const section =
    nav.find((item) => item.path === location.pathname)?.label ?? "Overview";
  if (location.pathname === "/sign-in")
    return <><div className="auth-theme"><ThemeButton dark={dark} onToggle={toggleTheme} /></div><AuthPage onAuthenticated={signedIn} /></>;
  return (
    <div className={`app-shell${profile ? "" : " public-shell"}`}>
      {profile && <aside className={`sidebar ${menuOpen ? "open" : ""}`}>
        <Sidebar close={() => setMenuOpen(false)} items={visibleNav} />
      </aside>}
      {menuOpen && (
        <button
          className="scrim"
          aria-label="Close navigation"
          onClick={() => setMenuOpen(false)}
        />
      )}
      <div className="main-area">
        <header className="topbar">
          <div className="topbar-left">
            {profile && <button
              className="icon-button desktop-hidden"
              aria-label="Open navigation"
              onClick={() => setMenuOpen(true)}
            >
              <Menu size={22} />
            </button>}
            {!profile && <NavLink to="/" className="public-brand"><Landmark size={22} /><strong>Harbour</strong></NavLink>}
            {profile && <span className="breadcrumb">
              <span className="breadcrumb-root">
                Workspace <span>/</span>
              </span>
              <strong>{section}</strong>
            </span>}
          </div>
          <div className="topbar-right">
            {profile && <label className="date-control">
              <CalendarDays size={17} />
              <span>As at</span>
              <input
                type="date"
                value={asAt}
                max={new Date().toISOString().slice(0, 10)}
                onChange={(event) => updateAsAt(event.target.value)}
                aria-label="As at date"
              />
            </label>}
            <ThemeButton dark={dark} onToggle={toggleTheme} />
            {profile ? (
              <div className="account-menu" ref={accountMenu}>
                <button className="profile profile-action" onClick={() => setProfileOpen(value => !value)} aria-label="Open account menu" aria-expanded={profileOpen} aria-controls="account-options">
                  <div className="profile-avatar">{profile.email.slice(0, 2).toUpperCase()}</div><div><strong>{profile.email}</strong><span>My account</span></div><ChevronDown size={15} />
                </button>
                {profileOpen && <nav id="account-options" className="account-options" aria-label="Your account"><p>{profile.email}</p>
                  {canSeeRoute("/portal", profile) && <NavLink to="/portal" onClick={() => setProfileOpen(false)}><Users size={18} />My membership</NavLink>}
                  <NavLink to="/settings" onClick={() => setProfileOpen(false)}><Settings size={18} />Settings</NavLink>
                  <button onClick={() => void signOut()}>Sign out</button>
                </nav>}
              </div>
            ) : (
              <NavLink to="/sign-in" className="sign-in-link">
                Sign in <ArrowRight size={16} />
              </NavLink>
            )}
          </div>
        </header>
        <main className="content">
          {profile && location.pathname !== '/accept-invitation' && !canSeeRoute(location.pathname, profile)
            ? <SimplePage title="Access required" subtitle="This page is not included in your current permissions." icon={ShieldCheck} heading="Choose an available page" description="Use the workspace menu to open a page your account can access." />
            : <Routes>
            <Route path="/" element={profile
              ? profile.permissions.includes("DASHBOARD_READ_ALL")
                ? <LiveDashboard asAt={asAt} onAsAtChange={updateAsAt} canExport={profile.permissions.includes("REPORT_EXPORT")} />
                : <MemberPortal asAt={asAt} />
              : <LandingPage />} />
            <Route
              path="/members"
              element={
                profile ? (
                  <LiveMembers profile={profile} asAt={asAt} />
                ) : (
                  <Members />
                )
              }
            />
            <Route
              path="/investments"
              element={
                profile ? (
                  <LiveInvestments profile={profile} />
                ) : (
                  <SimplePage
                    title="Investments"
                    subtitle="Sign in to view investments."
                    icon={Landmark}
                    heading="Sign in to continue"
                    description="Investment records are available after authentication."
                  />
                )
              }
            />
            <Route path="/reconciliation" element={profile ? <ReconciliationPage profile={profile} /> : <SimplePage title="Reconciliation" subtitle="Compare balances with the ledger." icon={ClipboardCheck} heading="Sign in to continue" description="Reconciliation is available to authorized union officers." />} />
            <Route path="/accept-invitation" element={<AcceptInvitation />} />
            <Route path="/imports" element={profile ? <ImportsPage profile={profile} /> : <SimplePage title="Imports" subtitle="Review source data before posting." icon={FileChartColumnIncreasing} heading="Sign in to continue" description="Imports require authorized access." />} />
            <Route path="/audit" element={profile ? <AuditPage profile={profile} /> : <SimplePage title="Audit log" subtitle="Trace recorded union actions." icon={ShieldCheck} heading="Sign in to continue" description="Audit records require authorized access." />} />
            <Route path="/accounts" element={profile ? <UserAdministration profile={profile} /> : <SimplePage title="Accounts and roles" subtitle="Manage account access." icon={ShieldCheck} heading="Sign in to continue" description="Account management requires authorized access." />} />
            <Route path="/portal" element={profile ? <MemberPortal asAt={asAt} /> : <SimplePage title="My membership" subtitle="Your balances and contributions." icon={Users} heading="Sign in to continue" description="Your membership records are private." />} />
            <Route path="/ledger" element={profile ? <LiveLedger profile={profile} asAt={asAt} /> : <SimplePage title="Ledger" subtitle="Sign in to view posted entries." icon={WalletCards} heading="Sign in to continue" description="Live ledger records are available to authorized accounts." />} />
            <Route path="/payments" element={profile ? <LivePayments profile={profile} /> : <SimplePage title="Payments" subtitle="Follow contributions from submission to confirmation." icon={CreditCard} heading="Sign in to continue" description="Submit receipts and follow payment status after authentication." />} />
            <Route
              path="/approvals"
              element={
                profile ? <ApprovalsPage profile={profile} /> : <SimplePage title="Approvals" subtitle="A second pair of eyes for money movements." icon={ClipboardCheck} heading="Sign in to review requests" description="Authorized requests are reviewed before money is posted." />
              }
            />
            <Route
              path="/reports"
              element={
                profile ? <ReportsPage profile={profile} asAt={asAt} /> : <SimplePage title="Reports" subtitle="Reliable figures for every point in time." icon={FileChartColumnIncreasing} heading="Sign in to view reports" description="Authorized report downloads use live member and ledger data." />
              }
            />
            <Route
              path="/settings"
              element={
                <SettingsPage
                  profile={profile}
                  signOutAll={() => signOut(true)}
                  onSessionEnded={() => { setProfile(null); setProfileOpen(false); navigate("/sign-in", { replace: true }); }}
                />
              }
            />
          </Routes>}
        </main>
      </div>
      {profile && <nav className={`bottom-nav${mainNav.length <= 4 && visibleNav.length === mainNav.length ? " compact" : ""}`} aria-label="Mobile navigation">
        {mainNav.slice(0, 3).map(({ label, path, icon: Icon }) => <NavLink key={path} to={path} end={path === "/"} className={({ isActive }) => `bottom-link${isActive ? " active" : ""}`}><Icon size={20} /><span>{label}</span></NavLink>)}
        <button className={`bottom-link${moreOpen ? " active" : ""}`} onClick={() => setMoreOpen(true)}><Menu size={20} /><span>More</span></button>
      </nav>}
      <ResponsiveOverlay open={moreOpen} onClose={() => setMoreOpen(false)} title="Workspace menu">
        <Sidebar close={() => setMoreOpen(false)} items={visibleNav} />
      </ResponsiveOverlay>

    </div>
  );
}

export default App
