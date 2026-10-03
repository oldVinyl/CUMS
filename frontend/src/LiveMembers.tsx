import Pagination from "./Pagination";
import { useCallback, useEffect, useState } from 'react'
import type { Dispatch, SetStateAction } from 'react'
import { ArrowRight, Plus, Search, Users } from 'lucide-react'
import type { Profile } from './auth-api'
import { csrfCookie } from './auth-api'
import { usePromiseToast, useToast } from './toast-context'
import { formatMoney } from './money'
import MemberStatement from './MemberStatement'

type Summary = { id: string; memberNumber: string; fullName: string; status: string }
type MemberPage = { items: Summary[]; page: number; size: number; hasNext: boolean }
type MemberBalance = { asAt: string; principal: string; income: string; equity: string }
type Detail = Summary & { email: string | null; phone: string | null; idType: string | null;
  idNumber: string | null; dateOfBirth: string | null; address: string | null;
  nextOfKinName: string | null; nextOfKinPhone: string | null;
  nextOfKinRelationship: string | null; joinDate: string; version: number }
type EditableFields = { fullName: string; email: string; phone: string; idType: string;
  idNumber: string; dateOfBirth: string; address: string; nextOfKinName: string;
  nextOfKinPhone: string; nextOfKinRelationship: string }

const blankFields: EditableFields = { fullName: '', email: '', phone: '', idType: '',
  idNumber: '', dateOfBirth: '', address: '', nextOfKinName: '',
  nextOfKinPhone: '', nextOfKinRelationship: '' }

function fromDetail(detail: Detail): EditableFields {
  return { fullName: detail.fullName, email: detail.email ?? '', phone: detail.phone ?? '',
    idType: detail.idType ?? '', idNumber: detail.idNumber ?? '',
    dateOfBirth: detail.dateOfBirth ?? '', address: detail.address ?? '',
    nextOfKinName: detail.nextOfKinName ?? '', nextOfKinPhone: detail.nextOfKinPhone ?? '',
    nextOfKinRelationship: detail.nextOfKinRelationship ?? '' }
}

function MemberFields({ values, setValues }: {
  values: EditableFields; setValues: Dispatch<SetStateAction<EditableFields>>
}) {
  const change = (key: keyof EditableFields, value: string) =>
    setValues(current => ({ ...current, [key]: value }))
  return <div className="member-form-grid">
    <label>Full name<input value={values.fullName} maxLength={200} onChange={event => change('fullName', event.target.value)} required /></label>
    <label>Email<input type="email" value={values.email} maxLength={320} onChange={event => change('email', event.target.value)} /></label>
    <label>Phone<input value={values.phone} maxLength={40} onChange={event => change('phone', event.target.value)} /></label>
    <label>Date of birth<input type="date" value={values.dateOfBirth} max={new Date().toISOString().slice(0, 10)} onChange={event => change('dateOfBirth', event.target.value)} /></label>
    <label>ID type<input value={values.idType} maxLength={60} onChange={event => change('idType', event.target.value)} /></label>
    <label>ID number<input value={values.idNumber} maxLength={120} onChange={event => change('idNumber', event.target.value)} /></label>
    <label>Address<input value={values.address} maxLength={2000} onChange={event => change('address', event.target.value)} /></label>
    <label>Next of kin<input value={values.nextOfKinName} maxLength={200} onChange={event => change('nextOfKinName', event.target.value)} /></label>
    <label>Next of kin phone<input value={values.nextOfKinPhone} maxLength={40} onChange={event => change('nextOfKinPhone', event.target.value)} /></label>
    <label>Relationship<input value={values.nextOfKinRelationship} maxLength={80} onChange={event => change('nextOfKinRelationship', event.target.value)} /></label>
  </div>
}

function BalanceCard({ balance, loading, asAt }: {
  balance: MemberBalance | null; loading: boolean; asAt: string
}) {
  return <div className="member-balance" aria-label="Approved member balance">
    <div><strong>Approved balance</strong><span>As at {asAt}</span></div>
    {loading ? <p>Loading ledger balance…</p> : balance ? <dl>
      <div><dt>Principal</dt><dd>{formatMoney(balance.principal)}</dd></div>
      <div><dt>Income</dt><dd>{formatMoney(balance.income)}</dd></div>
      <div><dt>Total equity</dt><dd>{formatMoney(balance.equity)}</dd></div>
    </dl> : <p>Balance unavailable.</p>}
  </div>
}

async function apiError(response: Response) {
  const body = await response.json().catch(() => null) as { message?: string } | null
  return new Error(body?.message ?? 'The request could not be completed.')
}

export default function LiveMembers({ profile, asAt }: { profile: Profile; asAt: string }) {
  const showToast = useToast()
  const showPromiseToast = usePromiseToast()
  const [members, setMembers] = useState<Summary[]>([])
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [selected, setSelected] = useState<Detail | null>(null)
  const [balance, setBalance] = useState<MemberBalance | null>(null)
  const [balanceLoading, setBalanceLoading] = useState(false)
  const [editing, setEditing] = useState(false)
  const [confirmStatus, setConfirmStatus] = useState(false)
  const [fields, setFields] = useState<EditableFields>(blankFields)
  const [editFields, setEditFields] = useState<EditableFields>(blankFields)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [showForm, setShowForm] = useState(false)
  const [joinDate, setJoinDate] = useState(new Date().toISOString().slice(0, 10))
  const canRead = profile.permissions.includes('MEMBER_READ')
  const canWrite = profile.permissions.includes('MEMBER_WRITE')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const params = new URLSearchParams({ page: String(page), size: '25' })
      if (search) params.set('search', search)
      const response = await fetch(`/api/v1/members?${params}`, { credentials: 'same-origin' })
      if (!response.ok) throw await apiError(response)
      const result = await response.json() as MemberPage
      setMembers(result.items)
      setHasNext(result.hasNext)
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not load members', { tone: 'error' })
    } finally {
      setLoading(false)
    }
  }, [page, search, showToast])

  useEffect(() => { if (canRead) void load() }, [canRead, load])

  const selectedId = selected?.id
  useEffect(() => {
    if (!selectedId) { setBalance(null); return }
    const controller = new AbortController()
    setBalance(null)
    setBalanceLoading(true)
    void (async () => {
      try {
        const response = await fetch(`/api/v1/members/${selectedId}/balance?asAt=${encodeURIComponent(asAt)}`,
          { credentials: 'same-origin', signal: controller.signal })
        if (!response.ok) throw await apiError(response)
        const result = await response.json() as MemberBalance
        if (typeof result.principal !== 'string' || typeof result.income !== 'string'
          || typeof result.equity !== 'string') throw new Error('Balance data was incomplete.')
        if (!controller.signal.aborted) setBalance(result)
      } catch (error) {
        if (!controller.signal.aborted) showToast('Could not load member balance',
          { detail: error instanceof Error ? error.message : 'Please try again.', tone: 'error' })
      } finally {
        if (!controller.signal.aborted) setBalanceLoading(false)
      }
    })()
    return () => controller.abort()
  }, [selectedId, asAt, showToast])

  const view = async (id: string) => {
    try {
      const response = await fetch(`/api/v1/members/${id}`, { credentials: 'same-origin' })
      if (!response.ok) throw await apiError(response)
      const detail = await response.json() as Detail
      setSelected(detail)
      setEditFields(fromDetail(detail))
      setEditing(false)
      setConfirmStatus(false)
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not open member', { tone: 'error' })
    }
  }

  const create = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setSaving(true)
    try {
      await showPromiseToast((async () => {
        const response = await fetch('/api/v1/members', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfCookie() },
          body: JSON.stringify({ ...fields,
            dateOfBirth: fields.dateOfBirth || null, joinDate }),
        })
        if (!response.ok) throw await apiError(response)
        setFields(blankFields); setShowForm(false)
        await load()
      })(), { loading: 'Adding member…', success: 'Member added', error: 'Could not add member' })
    } catch {
      // The promise toast displays the API error.
    } finally {
      setSaving(false)
    }
  }

  const saveEdit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selected) return
    setSaving(true)
    try {
      await showPromiseToast((async () => {
        const response = await fetch(`/api/v1/members/${selected.id}`, {
          method: 'PUT', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfCookie() },
          body: JSON.stringify({ version: selected.version, ...editFields,
            dateOfBirth: editFields.dateOfBirth || null }),
        })
        if (!response.ok) throw await apiError(response)
        await view(selected.id)
        await load()
      })(), { loading: 'Saving member…', success: 'Member updated', error: 'Could not update member' })
    } catch {
      // The promise toast displays the API error.
    } finally {
      setSaving(false)
    }
  }

  const changeStatus = async () => {
    if (!selected) return
    const nextStatus = selected.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    setSaving(true)
    try {
      await showPromiseToast((async () => {
        const response = await fetch(`/api/v1/members/${selected.id}/status`, {
          method: 'PATCH', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfCookie() },
          body: JSON.stringify({ version: selected.version, status: nextStatus }),
        })
        if (!response.ok) throw await apiError(response)
        await view(selected.id)
        await load()
      })(), { loading: 'Changing member status…',
        success: nextStatus === 'ACTIVE' ? 'Member reactivated' : 'Member deactivated',
        error: 'Could not change member status' })
    } catch {
      // The promise toast displays the API error.
    } finally {
      setSaving(false)
      setConfirmStatus(false)
    }
  }

  return <><div className="page-heading"><div><div className="eyebrow">HARBOUR CREDIT UNION</div><h1>Members</h1><p>People and records in your union.</p></div>{canWrite && <button className="primary-button" type="button" onClick={() => setShowForm(!showForm)}><Plus size={18} className={`member-add-icon${showForm ? ' open' : ''}`} /> {showForm ? 'Close form' : 'Add member'}</button>}</div>
    {!canRead ? <div className="surface empty-page"><Users size={32} /><h2>Member records are restricted</h2><p>Your current role does not include permission to view the union directory.</p></div> : <>
      {showForm && <form className="surface member-form" onSubmit={create}><h2>New member</h2><p>A short, unique member number is assigned when saved. Private fields are encrypted.</p><div className="member-form-grid"><label>Join date<input type="date" value={joinDate} max={new Date().toISOString().slice(0, 10)} onChange={event => setJoinDate(event.target.value)} required /></label></div><MemberFields values={fields} setValues={setFields} /><button className="primary-button" disabled={saving}>{saving ? 'Saving…' : 'Save member'} <ArrowRight size={17} /></button></form>}
      <section className="surface list-card"><div className="card-title-row"><div><h2>Member directory</h2><p>{loading ? 'Loading…' : `${members.length} members shown · page ${page + 1}`}</p></div></div><form className="member-search" onSubmit={event => { event.preventDefault(); setPage(0); setSearch(searchInput.trim()) }}><label htmlFor="member-search-input">Search by name or member number</label><div><input id="member-search-input" type="search" value={searchInput} maxLength={100} onChange={event => setSearchInput(event.target.value)} placeholder="Name or member number" /><button className="secondary-button" type="submit"><Search size={17} /> Search</button></div></form>{!loading && members.length === 0 ? <div className="empty-state"><Users size={28} /><strong>{search ? 'No matching members' : 'No members yet'}</strong><span>{search ? 'Try a different name or member number.' : 'Add your first member to start the directory.'}</span></div> : <><div className="table-scroll"><table><thead><tr><th>Member</th><th>Member no.</th><th>Status</th><th>Details</th></tr></thead><tbody>{members.map(member => <tr key={member.id}><td><strong>{member.fullName}</strong></td><td>{member.memberNumber}</td><td>{member.status}</td><td><button className="text-button" onClick={() => void view(member.id)}>View <ArrowRight size={15} /></button></td></tr>)}</tbody></table></div><div className="mobile-member-list">{members.map(member => <div className="mobile-member" key={member.id}><div className="mobile-member-top"><div><strong>{member.fullName}</strong><small>{member.memberNumber}</small></div><span className="ratio">{member.status}</span></div><button className="text-button" onClick={() => void view(member.id)}>View details <ArrowRight size={15} /></button></div>)}</div></>}{(page > 0 || hasNext) && <Pagination page={page} hasNext={hasNext} loading={loading} onPageChange={setPage} count={members.length} pageSize={25} label="Member pages" />}</section>
      {selected && <section className="surface member-detail" aria-label="Member details"><div className="card-title-row"><div><h2>{selected.fullName}</h2><p>{selected.memberNumber} · {selected.status}</p></div><div className="member-detail-actions">{canWrite && <button className="text-button" onClick={() => { if (editing) setEditFields(fromDetail(selected)); setEditing(!editing); setConfirmStatus(false) }}>{editing ? 'Cancel edit' : 'Edit member'}</button>}{canWrite && !editing && <button className="text-button" onClick={() => setConfirmStatus(!confirmStatus)}>{selected.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}</button>}<button className="text-button" onClick={() => { setSelected(null); setEditing(false); setConfirmStatus(false) }}>Close</button></div></div><BalanceCard balance={balance} loading={balanceLoading} asAt={asAt} />
        {profile.permissions.includes("REPORT_EXPORT") && profile.permissions.includes("LEDGER_READ") && <MemberStatement key={selected.id} memberId={selected.id} asAt={asAt} />}{confirmStatus && <div className="member-status-confirm"><p>{selected.status === 'ACTIVE' ? 'Deactivate this member? A linked portal account will be disabled and signed out.' : 'Reactivate this member record? A disabled portal account will need separate re-enabling.'}</p><button className="secondary-button" type="button" onClick={() => setConfirmStatus(false)}>Cancel</button><button className="primary-button" type="button" disabled={saving} onClick={() => void changeStatus()}>{saving ? 'Saving…' : `Confirm ${selected.status === 'ACTIVE' ? 'deactivation' : 'reactivation'}`}</button></div>}{editing ? <form className="member-form" onSubmit={saveEdit}><MemberFields values={editFields} setValues={setEditFields} /><button className="primary-button" disabled={saving}>{saving ? 'Saving…' : 'Save changes'} <ArrowRight size={17} /></button></form> : <dl><div><dt>Phone</dt><dd>{selected.phone ?? 'Not provided'}</dd></div><div><dt>Email</dt><dd>{selected.email ?? 'Not provided'}</dd></div><div><dt>Join date</dt><dd>{selected.joinDate}</dd></div><div><dt>Date of birth</dt><dd>{selected.dateOfBirth ?? 'Not provided'}</dd></div><div><dt>ID type</dt><dd>{selected.idType ?? 'Not provided'}</dd></div><div><dt>ID number</dt><dd>{selected.idNumber ?? 'Not provided'}</dd></div><div><dt>Address</dt><dd>{selected.address ?? 'Not provided'}</dd></div><div><dt>Next of kin</dt><dd>{selected.nextOfKinName ?? 'Not provided'}</dd></div><div><dt>Next of kin phone</dt><dd>{selected.nextOfKinPhone ?? 'Not provided'}</dd></div><div><dt>Relationship</dt><dd>{selected.nextOfKinRelationship ?? 'Not provided'}</dd></div></dl>}</section>}
    </>}
  </>
}
