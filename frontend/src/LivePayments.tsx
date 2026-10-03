import ResponsiveOverlay from "./ResponsiveOverlay";

import MemberPicker from "./MemberPicker";

import Pagination from "./Pagination";

import { useEffect, useState } from "react";

import { Plus, CreditCard } from "lucide-react";

import { csrfCookie, type Profile } from "./auth-api";

import { usePromiseToast, useToast } from "./toast-context";

import { formatMoney } from "./money";



type Method = { id: string; name: string; instructions: string; active: boolean };

type Payment = { id: string; memberId: string; memberNumber: string; fullName: string; amount: string; verifiedAmount: string | null;

 purpose: string; method: string; reference: string; status: string; paidDate: string; effectiveDate: string | null; proofId: string | null;

 reason: string | null; approvalRequestId: string | null; makerId: string | null; approvals: number; requiredApprovals: number; duplicate: boolean; provider: string | null; checkoutUrl: string | null; feeAmount: string };

async function api<T>(path: string, method = "GET", body?: object | FormData, key?: string, signal?: AbortSignal): Promise<T> {

 const form = body instanceof FormData;

 const r = await fetch(`/api/v1/payments${path}`, { method, credentials: "same-origin", signal,

  headers: method === "GET" ? undefined : { ...(form ? {} : { "Content-Type": "application/json" }), "X-CSRF-Token": csrfCookie(), ...(key ? { "Idempotency-Key": key } : {}) },

  body: body ? form ? body : JSON.stringify(body) : undefined });

 if (!r.ok) { const e = await r.json().catch(() => null) as { message?: string } | null; throw new Error(e?.message ?? "Payment request failed. Please retry."); }

 return r.status === 204 ? undefined as T : r.json() as Promise<T>;

}

export default function LivePayments({ profile }: { profile: Profile }) {

 const verifier = profile.permissions.includes("PAYMENT_VERIFY"), checker = profile.permissions.includes("APPROVE"), admin = profile.permissions.includes("SETTINGS_WRITE");

 const today = new Date().toISOString().slice(0, 10);

 const [onlineConfig, setOnlineConfig] = useState<{provider:string;enabled:boolean;development:boolean}|null>(null), [onlineOpen,setOnlineOpen] = useState(false), [onlineAmount,setOnlineAmount] = useState(""), [onlineMethod,setOnlineMethod] = useState("MOBILE_MONEY"), [onlineKey,setOnlineKey] = useState(crypto.randomUUID()), [pollUntil,setPollUntil] = useState(0);

 const [methods, setMethods] = useState<Method[]>([]), [payments, setPayments] = useState<Payment[]>([]), [own, setOwn] = useState<string | null>(null);

 const [page, setPage] = useState(0), [hasNext, setHasNext] = useState(false), [retry, setRetry] = useState(0), [loading, setLoading] = useState(true), [error, setError] = useState("");

 const [open, setOpen] = useState(false), [busy, setBusy] = useState(false);

 const [memberId, setMemberId] = useState(""), [methodId, setMethodId] = useState(""), [amount, setAmount] = useState(""), [paidDate, setPaidDate] = useState(today), [purpose, setPurpose] = useState("ADDITION"), [file, setFile] = useState<File | null>(null);

 const [methodName, setMethodName] = useState(""), [instructions, setInstructions] = useState("");

 const [selected, setSelected] = useState<Payment | null>(null), [actual, setActual] = useState(""), [reason, setReason] = useState(""), [confirm, setConfirm] = useState(false);

 const [submission, setSubmission] = useState<{ key: string; proofId?: string; reference?: string }>({ key: crypto.randomUUID() });

 const toast = useToast(), promiseToast = usePromiseToast();

 useEffect(() => {

  const controller = new AbortController(); setLoading(true); setError("");

  Promise.all([api<{payments: Payment[]; hasNext: boolean}>(`?page=${page}`, "GET", undefined, undefined, controller.signal), api<Method[]>("/methods", "GET", undefined, undefined, controller.signal), api<{memberId: string | null}>("/own-member", "GET", undefined, undefined, controller.signal), api<{provider:string;enabled:boolean;development:boolean}>("/online/config", "GET", undefined, undefined, controller.signal)])

   .then(([list, m, mine, config]) => { if (!controller.signal.aborted) { setPayments(list.payments); setHasNext(list.hasNext); setMethods(m); setOwn(mine.memberId); setOnlineConfig(config); } })

   .catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : "Could not load payments."); })

   .finally(() => { if (!controller.signal.aborted) setLoading(false); });

  return () => controller.abort();

 }, [page, retry]);

 const refresh = () => { setRetry(v => v + 1); setSelected(null); setConfirm(false); setReason(""); };

 const mutate = async (work: Promise<unknown>, success: string) => {

  setBusy(true); try { await promiseToast(work, { loading: "Saving…", success, error: "Could not complete payment action" }); refresh(); }

  catch { /* toast contains structured server feedback */ } finally { setBusy(false); }

 };

 const submit = async () => {

  const member = verifier ? memberId : own; if (!member || !file) return;

  setBusy(true);

  try { await promiseToast((async () => {

   let proof = submission.proofId;

   if (!proof) { const data = new FormData(); data.append("memberId", member); data.append("file", file); proof = (await api<{id:string}>("/proofs", "POST", data)).id; setSubmission(v => ({...v, proofId: proof})); }

   await api("", "POST", { memberId: member, methodId, amount, purpose, paidDate, proofId: proof }, submission.key);

  })(), { loading: "Uploading receipt and submitting payment…", success: "Payment submitted for verification", error: "Could not submit payment" });

   setOpen(false); setAmount(""); setFile(null); setSubmission({key: crypto.randomUUID()}); refresh();

  } catch { /* retain idempotency key and uploaded proof for a safe retry */ } finally {setBusy(false);}

 };

 useEffect(() => {

  if (!pollUntil || Date.now() >= pollUntil) return;

  const timer = window.setInterval(() => { if (Date.now() >= pollUntil) { window.clearInterval(timer); setPollUntil(0); toast("Payment still awaiting confirmation", {tone:"info",detail:"You can check its status below. Do not make another payment until the first is resolved."}); } else setRetry(v => v + 1); }, 3000);

  return () => window.clearInterval(timer);

 }, [pollUntil, toast]);

 const startOnline = async () => {

  const member = verifier ? memberId : own; if (!member) return; setBusy(true);

  try { const checkout = await promiseToast(api<{id:string;checkoutUrl:string|null}>("/online","POST",{memberId:member,amount:onlineAmount,purpose,method:onlineMethod},onlineKey), {loading:"Starting payment…",success:onlineConfig?.development?"Development payment created":"Payment started",error:"Could not start payment"});

   setOnlineOpen(false);setPollUntil(Date.now()+120000);refresh();

   if (checkout.checkoutUrl) window.location.assign(checkout.checkoutUrl);

  } catch { /* Keep key for safe retries. */ } finally {setBusy(false);}

 };

 const reserveReference = async () => { const member = verifier ? memberId : own; if (!member) return; setBusy(true);

  try { const result = await promiseToast(api<{reference:string}>("/manual-reference","POST",{memberId:member,methodId,amount,purpose},submission.key),{loading:"Creating transfer reference…",success:"Transfer reference ready",error:"Could not create reference"});setSubmission(v=>({...v,reference:result.reference}));setRetry(v=>v+1); }

  catch { /* toast */ } finally {setBusy(false);}

 };

 const resetSubmission = () => setSubmission({key: crypto.randomUUID()});

 const activeMethods = methods.filter(m => m.active);

 return <><header className="page-heading"><div><span className="eyebrow">Harbour Credit Union</span><h1>Payments</h1><p>Submit a receipt, follow its review, and see when your contribution is posted.</p></div>

  {(verifier || own) && <button className="primary-button" disabled={busy} aria-expanded={open} onClick={() => {setMemberId("");setOnlineOpen(false);setOpen(v => !v);}}><Plus size={18} style={{transform:open ? "rotate(45deg)" : undefined, transition:"transform 180ms ease"}} />{open ? "Close form" : "Submit receipt"}</button>}</header>

  <section className="surface investment-panel"><h2>Union bank and transfer details</h2><p>Use the union's published instructions, generate a transfer reference, then submit your receipt for verification.</p>{activeMethods.length ? activeMethods.map(method=><article className="payment-method" key={method.id}><h3>{method.name}</h3><p className="payment-instructions">{method.instructions}</p></article>) : <p>{loading ? "Loading transfer details…" : "The union has not published bank or mobile-money details yet. Contact the treasurer before sending funds."}</p>}</section>

  {!verifier && !own && !loading && !error && <section className="surface investment-panel"><h2>Member account not linked</h2><p>A linked membership is needed to submit and view your own payments.</p></section>}

  {(verifier || own) && <section className="surface investment-panel"><h2>Pay online</h2>{onlineConfig?.enabled ? <><p>{onlineConfig.development ? "Development simulator: no real money is collected. Simulated confirmation creates clearly marked development ledger entries." : "Pay by card or mobile money using secure Paystack checkout. Your balance updates after server confirmation."}</p><button className="secondary-button" disabled={busy} aria-expanded={onlineOpen} onClick={()=>{setMemberId("");setOpen(false);setOnlineOpen(v=>!v);}}>{onlineOpen ? "Close online form" : onlineConfig.development ? "Start a test payment" : "Pay now"}</button>

   {onlineOpen && <form className="investment-form" onSubmit={e=>{e.preventDefault();void startOnline();}}>{verifier&&<MemberPicker onSelect={member=>{setMemberId(member?.id??"");setOnlineKey(crypto.randomUUID());}} />}

   <label>Amount (GHS)<input required inputMode="decimal" value={onlineAmount} onChange={e=>{setOnlineAmount(e.target.value);setOnlineKey(crypto.randomUUID());}} /></label><label>Purpose<select value={purpose} onChange={e=>{setPurpose(e.target.value);setOnlineKey(crypto.randomUUID());}}><option value="ADDITION">Addition</option><option value="SEED">Seed</option></select></label><label>Method<select value={onlineMethod} onChange={e=>{setOnlineMethod(e.target.value);setOnlineKey(crypto.randomUUID());}}><option value="MOBILE_MONEY">Mobile money</option><option value="CARD">Card</option></select></label><button className="primary-button" disabled={busy || !(verifier ? memberId : own)}>Continue to {onlineConfig.development ? "test payment" : "secure checkout"}</button></form>}

   </> : <p>Online payments are not configured yet. Use a receipt submission when the union’s instructions are available.</p>}</section>}

  {open && <ResponsiveOverlay open={open} onClose={() => {setOpen(false);setMemberId("");}} title="Submit receipt"><section className="surface investment-panel"><h2>Receipt submission</h2><p>Use the payment method’s instructions. Amounts reach your balance after verification and a separate approval.</p>

   <form className="investment-form" onSubmit={e => {e.preventDefault(); void submit();}}>

    {verifier && <MemberPicker onSelect={member=>{setMemberId(member?.id??"");resetSubmission();}} />}

    <label>Payment method<select required value={methodId} onChange={e => {setMethodId(e.target.value); resetSubmission();}}><option value="">Choose a method</option>{activeMethods.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}</select></label>

    {methodId && <p className="payment-instructions">{methods.find(m => m.id === methodId)?.instructions}</p>}

    {!activeMethods.length && <p role="status">The union has not configured payment instructions yet.</p>}

    <label>Amount paid (GHS)<input required inputMode="decimal" pattern="[0-9]+([.][0-9]{1,2})?" value={amount} onChange={e => {setAmount(e.target.value); resetSubmission();}} /></label>

    <label>Purpose<select value={purpose} onChange={e => {setPurpose(e.target.value); resetSubmission();}}><option value="ADDITION">Addition</option><option value="SEED">Seed</option></select></label>

    <div className="payment-instructions">{submission.reference ? <p>Put <strong>{submission.reference}</strong> in the transfer narration. Then attach the receipt below.</p> : <><p>Before sending funds, generate a reference for the transfer narration.</p><button type="button" className="secondary-button" disabled={busy || !amount || !methodId || !(verifier ? memberId : own)} onClick={()=>void reserveReference()}>Create transfer reference</button></>}</div>

    <label>Date paid<input required type="date" min="2000-01-01" max={today} value={paidDate} onChange={e => {setPaidDate(e.target.value); }} /></label>

    <label>Receipt (JPEG, PNG or PDF; up to 10 MB)<input required type="file" accept="image/jpeg,image/png,application/pdf" onChange={e => {setFile(e.target.files?.[0] ?? null);setSubmission(v=>({...v,proofId:undefined}));}} /></label>

    <button className="primary-button" disabled={busy || !activeMethods.length || !(verifier ? memberId : own) || !file}>Submit for verification</button>

   </form></section></ResponsiveOverlay>}

  {admin && <section className="surface investment-panel"><h2>Payment instructions</h2><p>Add the bank or wallet details members should use. Only publish accounts approved by the union.</p>

   <form className="investment-form" onSubmit={e => {e.preventDefault();void mutate(api("/methods","POST",{name:methodName,instructions,active:true}),"Payment method saved");setMethodName("");setInstructions("");}}>

    <label>Method name<input required maxLength={120} value={methodName} onChange={e => setMethodName(e.target.value)} placeholder="Direct bank transfer" /></label>

    <label>Instructions<textarea required maxLength={3000} rows={3} value={instructions} onChange={e => setInstructions(e.target.value)} placeholder="Bank or wallet name, account details, and transfer instructions" /></label><button className="secondary-button" disabled={busy}>Add method</button>

   </form>{methods.map(m => <div className="payment-method" key={m.id}><div><strong>{m.name}</strong><p className="payment-instructions">{m.instructions}</p></div><button className="secondary-button" disabled={busy} onClick={() => void mutate(api("/methods","POST",{...m,active:!m.active}),m.active ? "Method disabled" : "Method enabled")}>{m.active ? "Disable" : "Enable"}</button></div>)}</section>}

  <section className="surface investment-panel"><div className="section-heading"><h2>{verifier || checker ? "Payment review and history" : "Your payments"}</h2><CreditCard size={20} /></div>

   {loading ? <p role="status">Loading payments…</p> : error ? <div role="alert"><p>{error}</p><button className="secondary-button" onClick={() => setRetry(v => v + 1)}>Retry</button></div> : !payments.length ? <p>No payments yet. Submitted receipts will appear here with their review status.</p> : payments.map(p => <article className="payment-record" key={p.id}>

    <div className="payment-record-heading"><div><strong>{p.reference}</strong><p>{verifier || checker ? `${p.memberNumber} · ${p.fullName} · ` : ""}{p.method} · {p.paidDate ? `Paid ${p.paidDate}` : p.effectiveDate ? `Confirmed ${p.effectiveDate}` : "Awaiting confirmation"}</p></div><strong>{formatMoney(p.amount)}</strong></div>

    <p><strong>{p.status.toLowerCase().replaceAll("_", " ")}</strong>{p.verifiedAmount && ` · Verified ${formatMoney(p.verifiedAmount)} · Posting date ${p.effectiveDate}`}</p>

    {p.reason && <p className="payment-instructions">Reviewer: {p.reason}</p>}{p.duplicate && (verifier || checker) && <p className="payment-warning" role="status">Possible duplicate: receipt hash or member, amount and payment date match another submission. Review both before posting.</p>}

    {p.provider === "FAKE" && <p className="payment-warning">Development simulation · Fee {formatMoney(p.feeAmount)} · No real money collected.</p>}

    <div className="investment-action-bar">{["POSTED","CONFIRMED"].includes(p.status) && <a className="secondary-button" href={`/api/v1/payments/${p.id}/confirmation.pdf`} download>Download confirmation PDF</a>}{p.provider && ["INITIATED","PENDING"].includes(p.status) && <>{onlineConfig?.development && p.provider === "FAKE" ? <button className="secondary-button" disabled={busy} onClick={()=>void mutate(api(`/${p.id}/simulate`,"POST"),"Test payment confirmed and posted")}>Simulate successful payment</button> : <><button className="secondary-button" disabled={busy} onClick={()=>void mutate(api(`/${p.id}/check`,"POST"),"Payment status checked")}>Check status</button>{p.checkoutUrl && <a className="secondary-button" href={p.checkoutUrl}>Resume checkout</a>}</>}</>}{p.proofId && <a className="secondary-button" href={`/api/v1/payments/proofs/${p.proofId}`} download>Download receipt</a>}

     {((verifier && ["SUBMITTED","UNDER_REVIEW"].includes(p.status)) || (checker && p.status === "VERIFIED_PENDING_APPROVAL")) && <button className="secondary-button" disabled={busy} onClick={() => {setSelected(p);setActual(p.verifiedAmount ?? p.amount);setReason("");setConfirm(false);}}>Review payment</button>}

     {p.status === "VERIFIED_PENDING_APPROVAL" && p.makerId === profile.userId && <button className="secondary-button" disabled={busy} onClick={() => void mutate(api(`/${p.id}/cancel`,"POST"),"Verification cancelled; payment awaits review")}>Cancel verification</button>}</div>

    {selected?.id === p.id && <div className="investment-edit"><h3>Review {p.reference}</h3>

      {p.status === "VERIFIED_PENDING_APPROVAL" ? <><p>{p.approvals} of {p.requiredApprovals} approvals. Approving credits {formatMoney(p.verifiedAmount ?? p.amount)} to this member’s principal.</p>

       {p.makerId === profile.userId ? <p>You verified this payment. A different approver must make the decision.</p> : <>{!confirm ? <button className="primary-button" disabled={busy} onClick={() => setConfirm(true)}>Review approval</button> : <div role="group" aria-label="Confirm payment approval"><p>Confirm receipt and bank evidence before posting {formatMoney(p.verifiedAmount ?? p.amount)}.</p><button className="primary-button" disabled={busy} onClick={() => void mutate(api(`/${p.id}/decide`,"POST",{decision:"APPROVE"}),"Approval recorded")}>Confirm approval</button><button className="secondary-button" onClick={() => setConfirm(false)}>Back</button></div>}

       <label>Reason to reject<textarea maxLength={1000} value={reason} onChange={e => setReason(e.target.value)} /></label><button className="secondary-button" disabled={busy || !reason.trim()} onClick={() => void mutate(api(`/${p.id}/decide`,"POST",{decision:"REJECT",comment:reason}),"Payment rejected")}>Reject payment</button></>}

      </> : <><label>Actual amount received (GHS)<input inputMode="decimal" value={actual} onChange={e => setActual(e.target.value)} /></label><p>Posting date: {today}. The submitted payment date is preserved; historical posting awaits the period-impact workflow.</p><button className="primary-button" disabled={busy || !actual} onClick={() => void mutate(api(`/${p.id}/verify`,"POST",{amount:actual,effectiveDate:today}),"Verification sent for independent approval")}>Verify and request approval</button>

       <label>Reason or information needed<textarea maxLength={1000} value={reason} onChange={e => setReason(e.target.value)} /></label><div className="investment-action-bar"><button className="secondary-button" disabled={busy || !reason.trim()} onClick={() => void mutate(api(`/${p.id}/review`,"POST",{action:"MORE_INFO",reason}),"More information requested")}>Request information</button><button className="secondary-button" disabled={busy || !reason.trim()} onClick={() => void mutate(api(`/${p.id}/review`,"POST",{action:"REJECT",reason}),"Payment rejected")}>Reject</button></div></>}

      <button className="secondary-button" onClick={() => setSelected(null)}>Close review</button>

    </div>}

   </article>)}

   <Pagination page={page} hasNext={hasNext} loading={loading} onPageChange={setPage} count={payments.length} pageSize={50} label="Payment pages" />

  </section>

 </>;

}
