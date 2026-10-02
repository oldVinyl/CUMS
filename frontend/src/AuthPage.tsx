import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, ArrowRight, Landmark, LockKeyhole, ShieldCheck } from 'lucide-react'
import QRCode from 'react-qr-code'
import { authPost, totpUri } from './auth-api'
import type { AuthStage, AuthStep } from './auth-api'
import { useToast } from './toast-context'
import GoogleButton from './GoogleButton'

function TotpSetupDetails({ secret, email }: { secret: string; email: string }) {
  return <div className="auth-totp-setup">
    <div className="auth-qr"><QRCode value={totpUri(secret, email)} size={168}
      title="Authenticator setup QR code" /></div>
    <p>Scan this code with your authenticator app, then enter the six digit code it shows.</p>
    <div className="auth-secret"><ShieldCheck size={19} /><div>
      <strong>Can’t scan it? Enter this setup key</strong><code>{secret}</code>
      <small>Keep this key private. It is shown only during setup.</small>
    </div></div>
  </div>
}

export default function AuthPage({ onAuthenticated }: { onAuthenticated: () => Promise<void> }) {
  const showToast = useToast()
  const [stage, setStage] = useState<AuthStage | 'LOGIN' | 'RECOVERY_CODES'>('LOGIN')
  const [challenge, setChallenge] = useState('')
  const [secret, setSecret] = useState('')
  const [busy, setBusy] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [code, setCode] = useState('')
  const [useRecovery, setUseRecovery] = useState(false)
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([])

  const copyCodes = async () => {
    try {
      await navigator.clipboard.writeText(recoveryCodes.join('\n'))
      showToast('Recovery codes copied', { tone: 'success' })
    } catch {
      showToast('Could not copy the codes', { tone: 'error' })
    }
  }

  const advance = async (step: AuthStep) => {
    if (step.stage === 'AUTHENTICATED') {
      if (step.recoveryCodes?.length) {
        setRecoveryCodes(step.recoveryCodes)
        setStage('RECOVERY_CODES')
        showToast('Authenticator setup complete', { tone: 'success' })
        return
      }
      try {
        await onAuthenticated()
      } catch (error) {
        setStage('LOGIN')
        setChallenge('')
        throw error
      }
      showToast('Signed in', { tone: 'success' })
    } else {
      setStage(step.stage)
      setChallenge(step.challenge ?? challenge)
      if (step.secret) setSecret(step.secret)
      setPassword('')
      setNewPassword('')
      setCode('')
    }
  }

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setBusy(true)
    try {
      if (stage === 'LOGIN') await advance(await authPost('login', { email, password }))
      else if (stage === 'PASSWORD_CHANGE') await advance(await authPost('password', { challenge, newPassword }))
      else if (stage === 'TOTP_SETUP' && !secret) {
        await advance(await authPost('totp/setup', { challenge }))
      } else if (stage === 'TOTP_SETUP') await advance(await authPost('totp/confirm', { challenge, code }))
      else await advance(await authPost('totp/verify', { challenge, code }))
    } catch (error) {
      showToast('Could not sign in', { detail: error instanceof Error ? error.message : 'Please try again.', tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  const googleCredential = async (idToken: string) => {
    setBusy(true)
    try {
      await advance(await authPost(stage === 'TOTP_SETUP' ? 'google/enroll' : 'google/login',
        stage === 'TOTP_SETUP' ? { challenge, idToken } : { idToken }))
    } catch (error) {
      showToast('Google sign-in failed', { detail: error instanceof Error ? error.message : 'Please try again.', tone: 'error' })
    } finally {
      setBusy(false)
    }
  }

  const title = stage === 'LOGIN' ? 'Welcome back' : stage === 'PASSWORD_CHANGE' ? 'Choose a new password'
    : stage === 'TOTP_SETUP' ? 'Protect your account' : stage === 'RECOVERY_CODES' ? 'Save your recovery codes'
      : 'Enter your security code'
  const detail = stage === 'LOGIN' ? 'Sign in to your credit union workspace.'
    : stage === 'PASSWORD_CHANGE' ? 'Your temporary password must be replaced before you continue.'
      : stage === 'TOTP_SETUP' ? 'Choose an authenticator app or Google to protect your account.'
        : stage === 'RECOVERY_CODES' ? 'Each code works once if you lose your authenticator. Store them somewhere private.'
          : 'Use the six digit code from your authenticator app.'

  return <main className="auth-layout">
    <div className="auth-side"><div className="auth-brand"><span><Landmark size={25} /></span><div><strong>Harbour</strong><small>Credit Union</small></div></div><div className="auth-side-copy"><span className="auth-kicker">A CLEARER WAY TO MANAGE TOGETHER</span><h1>Good records make stronger communities.</h1><p>Secure access to the people, decisions, and figures that matter.</p></div><small className="auth-side-foot">Built by Vinette · By Vinyl</small></div>
    <div className="auth-main"><Link to="/" className="auth-back"><ArrowLeft size={17} /> Return to preview</Link><section className="auth-card" aria-labelledby="auth-title"><div className="auth-symbol"><LockKeyhole size={24} /></div><h2 id="auth-title">{title}</h2><p>{detail}</p>{stage === 'RECOVERY_CODES' ? <div className="auth-codes"><ul>{recoveryCodes.map(item => <li key={item}><code>{item}</code></li>)}</ul><button className="auth-restart" type="button" onClick={copyCodes}>Copy all codes</button><button className="primary-button" type="button" onClick={() => { void onAuthenticated().then(() => showToast('Signed in', { tone: 'success' })).catch(error => showToast('Could not confirm session', { detail: error instanceof Error ? error.message : 'Please try again.', tone: 'error' })) }}>I saved my codes <ArrowRight size={18} /></button></div> : <form onSubmit={submit}>
      {stage === 'LOGIN' && <><label>Email address<input type="email" autoComplete="username" value={email} onChange={event => setEmail(event.target.value)} required /></label><label>Password<input type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required /></label></>}
      {stage === 'PASSWORD_CHANGE' && <label>New password<input type="password" autoComplete="new-password" value={newPassword} onChange={event => setNewPassword(event.target.value)} minLength={12} required /><small>Use a unique passphrase of at least 12 characters.</small></label>}
      {stage === 'TOTP_SETUP' && secret && <TotpSetupDetails secret={secret} email={email} />}
      {(stage === 'TOTP_VERIFY' || (stage === 'TOTP_SETUP' && secret)) && <label>{useRecovery ? 'Recovery code' : 'Six digit code'}<input type="text" inputMode={useRecovery ? 'text' : 'numeric'} autoComplete="one-time-code" pattern={useRecovery ? '[A-Za-z0-9_-]{22}' : '[0-9]{6}'} maxLength={useRecovery ? 22 : 6} value={code} onChange={event => setCode(useRecovery ? event.target.value.trim().slice(0, 22) : event.target.value.replace(/\D/g, '').slice(0, 6))} required /></label>}
      {stage === 'TOTP_VERIFY' && <button className="auth-restart" type="button" onClick={() => { setUseRecovery(!useRecovery); setCode('') }}>{useRecovery ? 'Use authenticator code' : 'Use a recovery code'}</button>}
      <button type="submit" className="primary-button" disabled={busy}>{busy ? 'Please wait…' : stage === 'LOGIN' ? 'Sign in' : stage === 'PASSWORD_CHANGE' ? 'Save password' : stage === 'TOTP_SETUP' && !secret ? 'Generate setup key' : 'Continue'} {!busy && <ArrowRight size={18} />}</button>
    </form>}{(stage === 'LOGIN' || stage === 'TOTP_SETUP') && <div className="auth-google-option"><span>Or continue with Google</span><GoogleButton key={stage} mode={stage === 'LOGIN' ? 'login' : 'enroll'} onCredential={idToken => { if (!busy) void googleCredential(idToken) }} /></div>}{stage === 'LOGIN' && import.meta.env.DEV && <p className="auth-dev-hint">Local development: use the bootstrap account configured in your project’s <code>.env</code> file.</p>}{stage !== 'LOGIN' && stage !== 'RECOVERY_CODES' && <button className="auth-restart" type="button" onClick={() => { setStage('LOGIN'); setChallenge(''); setSecret(''); setCode(''); setUseRecovery(false) }}>Start again</button>}</section><span className="auth-footnote">Access is limited to authorised credit union users.</span></div>
  </main>
}
