import { useCallback, useEffect, useState } from 'react'
import { ArrowRight, Plus, Users } from 'lucide-react'
import type { Profile } from './auth-api'
import { csrfCookie } from './auth-api'
import { useToast } from './toast-context'

type Summary = { id: string; memberNumber: string; fullName: string; status: string }
type Detail = Summary & { email: string | null; phone: string | null; idType: string | null;
  idNumber: string | null; dateOfBirth: string | null; address: string | null;
  nextOfKinName: string | null; nextOfKinPhone: string | null;
  nextOfKinRelationship: string | null; joinDate: string }

async function apiError(response: Response) {
  const body = await response.json().catch(() => null) as { message?: string } | null
  return new Error(body?.message ?? 'The request could not be completed.')
}

export default function LiveMembers({ profile }: { profile: Profile }) {
  const showToast = useToast()
  const [members, setMembers] = useState<Summary[]>([])
  const [selected, setSelected] = useState<Detail | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [showForm, setShowForm] = useState(false)
  const [number, setNumber] = useState('')
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [joinDate, setJoinDate] = useState(new Date().toISOString().slice(0, 10))
  const canRead = profile.permissions.includes('MEMBER_READ')
  const canWrite = profile.permissions.includes('MEMBER_WRITE')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const response = await fetch('/api/v1/members', { credentials: 'same-origin' })
      if (!response.ok) throw await apiError(response)
      setMembers(await response.json() as Summary[])
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not load members', { tone: 'error' })
    } finally {
      setLoading(false)
    }
  }, [showToast])

  useEffect(() => { if (canRead) void load() }, [canRead, load])

  const view = async (id: string) => {
    try {
      const response = await fetch(`/api/v1/members/${id}`, { credentials: 'same-origin' })
      if (!response.ok) throw await apiError(response)
      setSelected(await response.json() as Detail)
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not open member', { tone: 'error' })
    }
  }

  const create = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setSaving(true)
    try {
      const response = await fetch('/api/v1/members', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfCookie() },
        body: JSON.stringify({ memberNumber: number.trim(), fullName: name.trim(),
          phone: phone.trim() || null, joinDate }),
      })
      if (!response.ok) throw await apiError(response)
      setNumber(''); setName(''); setPhone(''); setShowForm(false)
      showToast('Member added', { tone: 'success' })
      await load()
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'Could not add member', { tone: 'error' })
    } finally {
      setSaving(false)
    }
  }

  return <><div className="page-heading"><div><div className="eyebrow">HARBOUR CREDIT UNION</div><h1>Members</h1><p>People and records in your union.</p></div>{canWrite && <button className="primary-button" type="button" onClick={() => setShowForm(!showForm)}><Plus size={18} /> {showForm ? 'Close form' : 'Add member'}</button>}</div>
    {!canRead ? <div className="surface empty-page"><Users size={32} /><h2>Member records are restricted</h2><p>Your current role does not include permission to view the union directory.</p></div> : <>
      {showForm && <form className="surface member-form" onSubmit={create}><h2>New member</h2><p>Start with the core record. Private fields are encrypted when saved.</p><div className="member-form-grid"><label>Member number<input value={number} maxLength={40} onChange={event => setNumber(event.target.value)} required /></label><label>Full name<input value={name} maxLength={200} onChange={event => setName(event.target.value)} required /></label><label>Phone (optional)<input value={phone} maxLength={40} onChange={event => setPhone(event.target.value)} /></label><label>Join date<input type="date" value={joinDate} max={new Date().toISOString().slice(0, 10)} onChange={event => setJoinDate(event.target.value)} required /></label></div><button className="primary-button" disabled={saving}>{saving ? 'Saving…' : 'Save member'} <ArrowRight size={17} /></button></form>}
      <section className="surface list-card"><div className="card-title-row"><div><h2>Member directory</h2><p>{loading ? 'Loading…' : `${members.length} members shown`}</p></div></div>{!loading && members.length === 0 ? <div className="empty-state"><Users size={28} /><strong>No members yet</strong><span>Add your first member to start the directory.</span></div> : <div className="table-scroll"><table><thead><tr><th>Member</th><th>Member no.</th><th>Status</th><th>Details</th></tr></thead><tbody>{members.map(member => <tr key={member.id}><td><strong>{member.fullName}</strong></td><td>{member.memberNumber}</td><td>{member.status}</td><td><button className="text-button" onClick={() => void view(member.id)}>View <ArrowRight size={15} /></button></td></tr>)}</tbody></table></div>}</section>
      {selected && <section className="surface member-detail" aria-label="Member details"><div className="card-title-row"><div><h2>{selected.fullName}</h2><p>{selected.memberNumber}</p></div><button className="text-button" onClick={() => setSelected(null)}>Close</button></div><dl><div><dt>Phone</dt><dd>{selected.phone ?? 'Not provided'}</dd></div><div><dt>Email</dt><dd>{selected.email ?? 'Not provided'}</dd></div><div><dt>Join date</dt><dd>{selected.joinDate}</dd></div><div><dt>ID type</dt><dd>{selected.idType ?? 'Not provided'}</dd></div><div><dt>ID number</dt><dd>{selected.idNumber ?? 'Not provided'}</dd></div><div><dt>Address</dt><dd>{selected.address ?? 'Not provided'}</dd></div><div><dt>Next of kin</dt><dd>{selected.nextOfKinName ?? 'Not provided'}</dd></div></dl></section>}
    </>}
  </>
}
