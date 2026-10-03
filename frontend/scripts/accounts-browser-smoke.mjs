// Isolated visual fixtures; never submits mutations to the running financial API.
import { mkdir, writeFile } from "node:fs/promises";

const tabs = await fetch("http://127.0.0.1:9223/json/list").then(response => response.json());
const ws = new WebSocket(tabs.find(tab => tab.type === "page").webSocketDebuggerUrl);
await new Promise(resolve => ws.addEventListener("open", resolve, { once: true }));
let nextId = 1;
const calls = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++; calls.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params }));
});
const today = new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Accra" });
const member = { id: "00000000-0000-4000-8000-000000000001", memberNumber: "DEMO-001", fullName: "Ama Demonstration Member", status: "ACTIVE" };
const permissions = ["DASHBOARD_READ_ALL", "MEMBER_READ", "MEMBER_WRITE", "LEDGER_READ", "LEDGER_POST", "LEDGER_REVERSE", "FISCAL_YEAR_CLOSE", "APPROVE", "INVESTMENT_READ", "REPORT_EXPORT", "SETTINGS_WRITE", "PAYMENT_VERIFY", "RECONCILE", "OWN_DATA_READ", "USER_ADMIN", "ROLE_ASSIGN"];
const pending = [{ id: "request-fixture", makerId: "another-maker", actionType: "LEDGER_POST", payload: { amount: "125.50", type: "SEED" },
  memberNumber: member.memberNumber, fullName: member.fullName, originalAmount: null, requiredApprovals: 1, approvals: 0 }];
const entries = [{ id: "entry-1", memberId: member.id, memberNumber: member.memberNumber, fullName: member.fullName, type: "ADDITION", component: "PRINCIPAL", amount: "125.50",
  effectiveDate: today, reference: "APR-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", narration: "Fictitious development contribution", reversalOf: null, reversed: false, canReverse: true },
{ id: "entry-2", memberId: member.id, memberNumber: member.memberNumber, fullName: member.fullName, type: "INCOME_ALLOCATION", component: "INCOME", amount: "-5.00",
  effectiveDate: today, reference: "INV-MAT-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-ffffffff-1111-2222-3333-444444444444", narration: "Fictitious investment loss", reversalOf: null, reversed: false, canReverse: false }];
ws.addEventListener("message", async event => {
  try {
  const message = JSON.parse(event.data);
  if (message.id) {
    const call = calls.get(message.id); calls.delete(message.id);
    if (message.error) call.reject(new Error(message.error.message)); else call.resolve(message.result);
  } else if (message.method === "Fetch.requestPaused") {
    const { requestId, request } = message.params;
    const path = new URL(request.url).pathname;
    let body;
    if (request.method !== "GET") throw new Error("Visual smoke must never submit a financial mutation");
    if (path === "/api/v1/auth/me") body = { userId: "visual-checker", email: "visual@invalid.example", preferredSignInMethod: "TOTP", permissions };
    else if (path === "/api/v1/members") body = { items: [member], page: 0, size: 25, hasNext: false };
    else if (path === "/api/v1/users") body={users:[{id:"visual-checker",email:"admin@invalid.example",status:"ACTIVE",memberNumber:member.memberNumber,roles:[{id:"admin-role",code:"ADMIN",startDate:today,endDate:null}]},{id:"target-member",email:"member@invalid.example",status:"ACTIVE",memberNumber:"DEMO-002",roles:[{id:"member-role",code:"MEMBER",startDate:today,endDate:null}]}],page:0,hasNext:false};
    else if (path === "/api/v1/users/roles/pending") body=[{id:"role-request",makerId:"other-maker",payload:{operation:"ASSIGN",userId:"target-member",roleCode:"TREASURER",reason:"Fictitious role request for independent visual review"},approvals:0,requiredApprovals:1}];
    else if (path === "/api/v1/payments/policy") body={organisationName:"Harbour Credit Union",contactEmail:"contact@invalid.example",contactPhone:"+233201234567",requiredApprovals:1,feeBearer:"UNION",paymentMin:"1.00",paymentMax:"1000000.00"};
    else if (path === "/api/v1/payments") body = {payments:[{id:"payment-fixture",memberId:member.id,memberNumber:member.memberNumber,fullName:member.fullName,amount:"125.50",verifiedAmount:"125.25",purpose:"ADDITION",method:"Bank transfer",reference:"CU-2026-000123",status:"VERIFIED_PENDING_APPROVAL",paidDate:today,effectiveDate:today,proofId:"proof-fixture",reason:null,approvalRequestId:"request-payment",makerId:"other-maker",approvals:0,requiredApprovals:1,duplicate:true,provider:null,checkoutUrl:null,feeAmount:"0.00"}],page:0,hasNext:false};
    else if (path === "/api/v1/payments/methods") body=[{id:"method-fixture",name:"Development bank transfer",instructions:"Fictitious account only.\nAdd the generated reference to the transfer narration.",active:true}];
    else if (path === "/api/v1/payments/own-member") body={memberId:member.id};
    else if (path === "/api/v1/payments/online/config") body={provider:"FAKE",enabled:true,development:true};
    else if (path === "/api/v1/reconciliations/preview") body={equity:"1500.00",principal:"1400.00",income:"100.00",integrityDifference:"0.00",invested:"1000.00",expectedCash:"500.00",sourceFingerprint:"fixture"};
    else if (path === "/api/v1/reconciliations") body={records:[{id:"record-fixture",asAt:today,actualHoldings:"1000.00",actualCash:"490.00",snapshot:{equity:"1500.00",invested:"1000.00",expectedCash:"500.00",integrityDifference:"0.00",holdingsDifference:"0.00",cashDifference:"-10.00"},notes:"Fictitious development reconciliation. Bank fee pending evidence.",makerId:"other-maker",status:"PENDING",approvals:0,requiredApprovals:1}],page:0,hasNext:false};
    else if (path === "/api/v1/portal") body={memberId:member.id,memberNumber:member.memberNumber,fullName:member.fullName,asAt:today,balanceBf:{principal:"1000",income:"50",equity:"1050"},ytd:{principal:"400",income:"50",equity:"450"},total:{principal:"1400",income:"100",equity:"1500"},roePercent:"7.14",equityRatioPercent:"20.00",trend:[{date:today,equity:"1500"}],allocations:[{investmentId:"investment-fixture",institution:"Demonstration Treasury",tenor:"91_DAY",status:"POSTED",purchaseDate:today,maturityDate:today,participation:"1000",costShare:"980",expectedIncome:"75.25",actualIncome:null}]};
    else if (path === "/api/v1/portal/ledger") body={entries,hasNext:false,page:0};
    else if (path === "/api/v1/portal/profile") body={version:0,email:"demo@invalid.example",phone:"+233201234567",nextOfKinName:"Demonstration kin",nextOfKinPhone:"+233209876543",nextOfKinRelationship:"Sibling"};
    else if (path === "/api/v1/ledger") body = { entries, page: 0, hasNext: false };
    else if (path === "/api/v1/ledger/approvals") body = pending;
    else if (path === "/api/v1/ledger/fiscal-years") body = [{ year: Number(today.slice(0, 4)), status: "OPEN" }, { year: Number(today.slice(0, 4)) - 1, status: "OPEN" }];
    else if (path === "/api/v1/settings/rollover") body = { defaultRolloverPolicy: "ROLL_PRINCIPAL_AND_INCOME" };
    if (body !== undefined) await send("Fetch.fulfillRequest", { requestId, responseCode: 200, responseHeaders: [{ name: "Content-Type", value: "application/json" }], body: Buffer.from(JSON.stringify(body)).toString("base64") });
    else await send("Fetch.fulfillRequest", { requestId, responseCode: 404, responseHeaders: [{ name: "Content-Type", value: "application/json" }], body: Buffer.from('{"message":"No visual fixture for this request"}').toString("base64") });
  }
  } catch (error) { if (!error.message.includes("Invalid InterceptionId")) throw error; }
});
const evaluate = async expression => (await send("Runtime.evaluate", { expression, returnByValue: true })).result.value;
const waitFor = async expression => {
  for (let attempt = 0; attempt < 70; attempt++) {
    if (await evaluate(expression)) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(`UI did not become ready: ${expression}`);
};
await send("Page.enable");
await send("Fetch.enable", { patterns: [{ urlPattern: "*/api/v1/*" }] });
await mkdir("artifacts/ui-check/accounts", { recursive: true });
try {
  for (const width of [320, 375, 768, 1024, 1440]) for (const theme of ["light", "dark"]) for (const route of ["/accounts", "/settings", "/accept-invitation"]) {
    await send("Emulation.setDeviceMetricsOverride", { width, height: 1000, deviceScaleFactor: 1, mobile: width < 600 });
    await send("Page.navigate", { url: `http://localhost:5173${route}${route === "/accept-invitation" ? "#token=" + "a".repeat(64) : ""}` });
    await waitFor(`document.querySelector('h1')?.textContent === '${route === '/accounts' ? 'Accounts and roles' : route === '/accept-invitation' ? 'Welcome to your membership' : route.slice(1, 2).toUpperCase() + route.slice(2)}'`);
    if (route === "/accounts") {
      await waitFor("document.body.textContent.includes('Fictitious role request')");
      await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Request role assignment').click()");
      await waitFor("document.body.textContent.includes('Submit role request')");
    }
    if (route === "/settings") await waitFor("document.body.textContent.includes('Minimum online payment')");
    if (route === "/payments") {
      await waitFor("document.body.textContent.includes('CU-2026-000123')");
      await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent==='Submit receipt').click()");
      await waitFor("!!document.querySelector('input[type=file]')");
    }
    if (route === "/portal") await waitFor("document.body.textContent.includes('Your contact details')");
    if (route === "/reconciliation") await waitFor("document.body.textContent.includes('Fictitious development reconciliation')");
    if (route === "/reports") {
      await waitFor("!!document.querySelector('select option[value=\"00000000-0000-4000-8000-000000000001\"]')");
      await evaluate(`const select=document.querySelector('select');select.value='${member.id}';select.dispatchEvent(new Event('change',{bubbles:true}));`);
      await waitFor("document.body.textContent.includes('Member statement')");
    }
    if (route === "/ledger") {
      await waitFor("!!document.querySelector('.ledger-entry')");
      await evaluate("[...document.querySelectorAll('button')].find(button=>button.textContent.includes('Add contribution')).click()");
      await waitFor("!!document.querySelector('select option[value=\"00000000-0000-4000-8000-000000000001\"]')");
    }
    if (route === "/approvals") {
      await waitFor("!!document.querySelector('.ledger-entry')");
      await evaluate("[...document.querySelectorAll('button')].find(button=>button.textContent==='Review approval').click()");
      await waitFor("document.body.textContent.includes('Confirm and approve')");
    }
    await evaluate(`localStorage.setItem('theme','${theme}');document.documentElement.dataset.theme='${theme}'`);
    await new Promise(resolve => setTimeout(resolve, 150));
    if (await evaluate("document.documentElement.scrollWidth > innerWidth")) throw new Error(`Horizontal page overflow: ${route} ${width} ${theme}`);
    const shot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
    await writeFile(`artifacts/ui-check/accounts/${route.slice(1)}-${width}-${theme}.png`, Buffer.from(shot.data, "base64"));
    console.log(`PASS ${route} ${width}px ${theme}; no page overflow`);
  }
} finally { ws.close(); }
