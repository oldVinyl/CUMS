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
let memberOnly=false, signedOut=false;
const permissions = ["USER_ADMIN", "ROLE_ASSIGN", "INVESTMENT_WRITE", "DASHBOARD_READ_ALL", "MEMBER_READ", "MEMBER_WRITE", "LEDGER_READ", "LEDGER_POST", "LEDGER_REVERSE", "FISCAL_YEAR_CLOSE", "PERIOD_UNLOCK", "APPROVE", "INVESTMENT_READ", "REPORT_EXPORT", "SETTINGS_WRITE", "PAYMENT_VERIFY", "RECONCILE", "OWN_DATA_READ"];
const pending = [{ id: "unlock-fixture", makerId: "another-maker", actionType: "PERIOD_UNLOCK", payload: { year: Number(today.slice(0, 4))-1, reason: "Correct omitted historical source contribution after independent review." }, memberNumber: null, fullName: null, originalAmount: null, requiredApprovals: 2, approvals: 1 }, { id: "request-fixture", makerId: "another-maker", actionType: "LEDGER_POST", payload: { amount: "125.50", type: "SEED" },
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
    if(path==="/api/v1/auth/me"&&signedOut){await send("Fetch.fulfillRequest",{requestId,responseCode:401,responseHeaders:[{name:"Content-Type",value:"application/json"}],body:Buffer.from('{"code":"AUTH_REQUIRED"}').toString("base64")});return;}
    if (path === "/api/v1/auth/me") body = { userId: "visual-checker", email: "visual@invalid.example", preferredSignInMethod: "TOTP", permissions:memberOnly?["OWN_DATA_READ"]:permissions };
    else if (path === "/api/v1/members") body = { items: [member], page: 0, size: 25, hasNext: false };
    else if (path === `/api/v1/members/${member.id}/balance`) body={asAt:today,principal:"1400",income:"100",equity:"1500"};
    else if (path === `/api/v1/members/${member.id}`) body={...member,email:"demo@invalid.example",phone:"+233201234567",idType:null,idNumber:null,dateOfBirth:null,address:null,nextOfKinName:null,nextOfKinPhone:null,nextOfKinRelationship:null,joinDate:today,version:0};
    else if (path === "/api/v1/investments" || path === "/api/v1/investments/approvals/pending") body=[];
    else if (path === "/api/v1/users") body={users:[],hasNext:false};
    else if (path === "/api/v1/users/roles/pending") body=[];
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
    else if (/^\/api\/v1\/ledger\/fiscal-years\/\d+\/snapshots$/.test(path)) body={ entries: [{memberNumber: member.memberNumber,fullName:member.fullName,principal:"1400.0000",income:"100.0000",equity:"1500.0000",revision:3,ledgerEquity:"1500.0000",reconciled:true}],hasNext:false,page:0};
    else if (path === "/api/v1/dashboard") body={asAt:today,fiscalYear:Number(today.slice(0,4)),members:[],totals:{fullName:"Total",balanceBf:{principal:"0",income:"0",equity:"0"},ytd:{principal:"0",income:"0",equity:"0"},total:{principal:"0",income:"0",equity:"0"},roePercent:"0",equityRatioPercent:"0"},trend:[{date:"2026-01-31",equity:"1000"},{date:"2026-06-30",equity:"1250"},{date:today,equity:"1500"}],balanceForwardSnapshot:{fiscalYear:Number(today.slice(0,4))-1,state:"REVIEW_REQUIRED",snapshotCount:11,missingMembers:1,mismatchedMembers:2}};
    else if (path === "/api/v1/ledger/fiscal-years") body = [{ year: Number(today.slice(0, 4)), status: "OPEN" }, { year: Number(today.slice(0, 4)) - 1, status: "CLOSED" }];
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
  console.log(await evaluate("JSON.stringify({focus:document.activeElement?.outerHTML,dialog:document.querySelector('[role=dialog]')?.outerHTML})"));
  throw new Error(`UI did not become ready: ${expression}`);
};
await send("Page.enable");
await send("Page.bringToFront");
await send("Emulation.setFocusEmulationEnabled", {enabled:true});
await send("Fetch.enable", { patterns: [{ urlPattern: "*/api/v1/*" }] });
await mkdir("artifacts/ui-check/workspace", { recursive: true });
const clickButton = async text => {
  await waitFor(`[...document.querySelectorAll('button')].some(b=>b.textContent.trim()===${JSON.stringify(text)})`);
  await evaluate(`[...document.querySelectorAll('button')].find(b=>b.textContent.trim()===${JSON.stringify(text)}).click()`);
};
const outsideClose = async width => {
  await send("Input.dispatchMouseEvent", {type:"mousePressed",x:5,y:width<1024?15:150,button:"left",clickCount:1});
  await send("Input.dispatchMouseEvent", {type:"mouseReleased",x:5,y:width<1024?15:150,button:"left",clickCount:1});
  await waitFor("!document.querySelector('[role=dialog]')");
};
const checkOverlay = async (width, theme, name) => {
  await waitFor("!!document.querySelector('[role=dialog]')");
  await new Promise(resolve=>setTimeout(resolve,220));
  const box=await evaluate("(()=>{const r=document.querySelector('[role=dialog]').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,overflow:document.querySelector('[role=dialog]').scrollWidth>r.width+1}})()");
  if(box.overflow || (width>=1024 ? box.x<width/3 || box.y!==0 : box.x!==0 || box.y<20)) throw new Error(`Incorrect overlay ${name} ${width}: ${JSON.stringify(box)}`);
  if(await evaluate("document.documentElement.scrollWidth>innerWidth")) throw new Error(`Page overflow ${name} ${width}`);
  const shot=await send("Page.captureScreenshot",{format:"png"});
  await writeFile(`artifacts/ui-check/workspace/${name}-${width}-${theme}.png`,Buffer.from(shot.data,"base64"));
};
try {
  for(const width of (process.env.CUMS_UI_QUICK ? [375] : [320,375,768,1024,1440])) for(const theme of ["light","dark"]) {
    await send("Emulation.setDeviceMetricsOverride",{width,height:1000,deviceScaleFactor:1,mobile:width<600});
    for(const route of ["/members","/investments","/ledger","/payments","/reports","/portal","/","/accounts"]) {
      await send("Page.navigate",{url:`http://localhost:5173${route}`});
      await waitFor("!!document.querySelector('.account-menu')");
      await evaluate(`localStorage.setItem('theme','${theme}');document.documentElement.dataset.theme='${theme}'`);
      if(route==="/members") {
        await clickButton("Add member"); await checkOverlay(width,theme,"add-member"); await outsideClose(width);
        await waitFor("!!document.querySelector('.table-scroll tbody tr')");
        await evaluate("document.querySelector('.table-scroll tbody tr button').click()");
        await checkOverlay(width,theme,"member-details"); await outsideClose(width);
      }
      if(route==="/investments") {await clickButton("New draft"); await checkOverlay(width,theme,"investment-draft"); await outsideClose(width);}
      if(route==="/ledger") {await clickButton("Add contribution"); await checkOverlay(width,theme,"contribution"); await evaluate("document.querySelector('[role=combobox]').focus()"); await waitFor("!!document.querySelector('[role=option]')"); await outsideClose(width);}
      if(route==="/payments") {await clickButton("Submit receipt"); await checkOverlay(width,theme,"receipt"); await outsideClose(width);}
      if(route==="/reports") {await waitFor("!!document.querySelector('[role=combobox]')"); await evaluate("document.querySelector('[role=combobox]').focus()"); await waitFor("!!document.querySelector('[role=option]')"); await evaluate("document.querySelector('[role=option]').click()"); await waitFor("document.body.textContent.includes('Member statement')");}
      if(route==="/portal") {await waitFor("document.body.textContent.includes('Your transactions')"); if(!await evaluate("(()=>{const h=[...document.querySelectorAll('h2,h3')].map(h=>h.textContent);return h.indexOf('Your contact details')<h.indexOf('Your transactions')&&h.indexOf('Member statement')<h.indexOf('Your transactions')})()")) throw new Error("Portal section order incorrect");}
      if(route==="/") {await waitFor("!!document.querySelector('.equity-chart svg')"); await evaluate("document.querySelector('[aria-label=\"Open account menu\"]').click()"); await waitFor("!!document.querySelector('[aria-label=\"Your account\"]')"); if(!await evaluate("getComputedStyle(document.querySelector('.theme-toggle')).display!=='none'")) throw new Error("Mobile theme hidden"); await evaluate("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))");}
      await new Promise(resolve=>setTimeout(resolve,120));
      if(await evaluate("document.documentElement.scrollWidth>innerWidth")) throw new Error(`Page overflow ${route} ${width} ${theme}`);
      const shot=await send("Page.captureScreenshot",{format:"png",captureBeyondViewport:true});
      await writeFile(`artifacts/ui-check/workspace/${route.slice(1)||'overview'}-${width}-${theme}.png`,Buffer.from(shot.data,"base64"));
      console.log(`PASS ${route} ${width}px ${theme}`);
    }
  }
  await send("Emulation.setDeviceMetricsOverride",{width:375,height:1000,deviceScaleFactor:1,mobile:true});
  await send("Page.navigate",{url:"http://localhost:5173/members"}); await waitFor("!!document.querySelector('.account-menu')");
  await clickButton("Add member"); await waitFor("!!document.querySelector('[role=dialog]')"); await new Promise(resolve=>setTimeout(resolve,250));
  const handle=await evaluate("(()=>{const r=document.querySelector('.sheet-drag-handle').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()");
  await send("Input.dispatchMouseEvent",{type:"mousePressed",...handle,button:"left",clickCount:1});
  await send("Input.dispatchMouseEvent",{type:"mouseMoved",x:handle.x,y:handle.y+10,button:"left",buttons:1});
  await send("Input.dispatchMouseEvent",{type:"mouseReleased",x:handle.x,y:handle.y+10,button:"left",clickCount:1});
  if(!await evaluate("!!document.querySelector('[role=dialog]')")) throw new Error("Short drag unexpectedly closes the sheet");
  await send("Input.dispatchMouseEvent",{type:"mousePressed",...handle,button:"left",clickCount:1});
  await send("Input.dispatchMouseEvent",{type:"mouseMoved",x:handle.x,y:handle.y+130,button:"left",buttons:1});
  await send("Input.dispatchMouseEvent",{type:"mouseReleased",x:handle.x,y:handle.y+130,button:"left",clickCount:1});
  await waitFor("!document.querySelector('[role=dialog]')"); console.log("PASS mobile drag dismissal");
  memberOnly=true;
  await send("Page.navigate",{url:"http://localhost:5173/payments"}); await waitFor("document.body.textContent.includes('Your payments')"); await clickButton("Submit receipt");
  if(await evaluate("!!document.querySelector('[role=combobox]')")) throw new Error("Ordinary member can select another member"); await outsideClose(375);
  if(!await evaluate("document.body.textContent.includes('Fictitious account only.')")) throw new Error("Published instructions missing for member");
  await evaluate("document.querySelector('[aria-label=\"Open account menu\"]').click()"); await waitFor("!!document.querySelector('.account-options')");
  let shot=await send("Page.captureScreenshot",{format:"png"}); await writeFile("artifacts/ui-check/workspace/member-profile-menu-375.png",Buffer.from(shot.data,"base64"));
  console.log("PASS member payments, bank details and mobile profile menu");
  signedOut=true;
  for(const width of [320,375,1440]) {
    await send("Emulation.setDeviceMetricsOverride",{width,height:1000,deviceScaleFactor:1,mobile:width<600});
    await send("Page.navigate",{url:"http://localhost:5173/"}); await waitFor("!!document.querySelector('.public-shell') && !document.querySelector('.account-menu')");
    if(await evaluate("!!document.querySelector('.sidebar') || document.documentElement.scrollWidth>innerWidth || document.querySelector('.sign-in-link').getBoundingClientRect().right>innerWidth")) throw new Error(`Signed-out layout retains sidebar or overflows ${width}`);
    shot=await send("Page.captureScreenshot",{format:"png"}); await writeFile(`artifacts/ui-check/workspace/public-overview-${width}.png`,Buffer.from(shot.data,"base64"));
    console.log(`PASS signed-out full-width layout ${width}`);
  }
} finally { ws.close(); }
