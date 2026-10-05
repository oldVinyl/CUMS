import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
const today = new Date().toLocaleDateString("en-CA", { timeZone: "Africa/Accra" });
const zero = { principal: "0", income: "0", equity: "0" };
const row = { memberId: null, memberNumber: null, fullName: "Total", balanceBf: zero, ytd: zero, total: zero, roePercent: "0", equityRatioPercent: "0" };
async function fixtures(page, initiallySignedIn) {
 let signedIn=initiallySignedIn; const unexpected=[];
 await page.route("**/api/v1/**", async route => {
  if (new URL(route.request().url()).pathname === '/api/v1/organization/branding') {
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({organisationName:'Harbour Credit Union',primaryColour:'#083a4f',currencyDisplay:'SYMBOL',logoDataUrl:'',passwordMinLength:12})}); return;
  }
  const request=route.request(); const path=new URL(request.url()).pathname;
  let body, status=200;
  if(path==="/api/v1/auth/me") { body=signedIn ? {userId:"anonymous-browser-account",email:"fixture@invalid.example",permissions:["OWN_DATA_READ","DASHBOARD_READ_ALL","MEMBER_READ","MEMBER_WRITE"],preferredSignInMethod:"TOTP"} : {code:"AUTH_REQUIRED"}; status=signedIn?200:401; }
  else if(path==="/api/v1/auth/login" && request.method()==="POST") body={stage:"TOTP_VERIFY",challenge:"anonymous-one-use-challenge",secret:null,recoveryCodes:null};
  else if(path==="/api/v1/auth/totp/verify" && request.method()==="POST") { signedIn=true;body={stage:"AUTHENTICATED",challenge:null,secret:null,recoveryCodes:null}; }
  else if(path==="/api/v1/dashboard" && request.method()==="GET") body={asAt:today,fiscalYear:Number(today.slice(0,4)),members:[],totals:row,trend:[],developmentData:false};
  else if(path==="/api/v1/members" && request.method()==="GET") body={items:[],page:0,size:25,hasNext:false};
  else if(path==="/api/v1/auth/security" && request.method()==="GET") body={totpEnabled:true,recoveryCodesRemaining:8,passkeys:[{id:"opaque-browser-fixture",createdAt:"2026-01-01T12:00:00Z",lastUsedAt:null,backedUp:true}]};
  else if(path==="/api/v1/auth/security/recovery-codes" && request.method()==="POST") body={recoveryCodes:["anonymous-fixture-code-01","anonymous-fixture-code-02"]};
  else if(path.startsWith("/api/v1/auth/google/")) {body={enabled:false};}
  else {unexpected.push(`${request.method()} ${path}`);body={message:"Unexpected fixture request"};status=404;}
  await route.fulfill({status,contentType:"application/json",body:JSON.stringify(body),headers:{"Cache-Control":"no-store"}});
 });
 return unexpected;
}
async function accessible(page) {
 const scan=await new AxeBuilder({page}).withTags(["wcag2a","wcag2aa","wcag21a","wcag21aa"]).analyze();
 expect(scan.violations.map(({id,nodes})=>({id,nodes:nodes.map(node=>node.target)}))).toEqual([]);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
}
test("public landing and sign-in are accessible without financial navigation",async({page})=>{
 const unexpected=await fixtures(page,false);await page.goto("/");
 await expect(page.getByRole("heading",{name:"Harbour Credit Union"})).toBeVisible();
 await expect(page.getByRole("navigation",{name:"Main navigation"})).toHaveCount(0);
 await expect(page.getByRole("link",{name:"Log in"})).toBeVisible();await accessible(page);
 await page.getByRole("link",{name:"Log in"}).click();await expect(page.getByLabel("Email address")).toBeVisible();await accessible(page);
 expect(unexpected).toEqual([]);
});
test("password and recovery-code sign-in lands on overview",async({page})=>{
 const unexpected=await fixtures(page,false);await page.goto("/sign-in");
 await page.getByLabel("Email address").fill("fixture@invalid.example");await page.getByLabel("Password",{exact:true}).fill("Anonymous browser passphrase");
 await page.getByRole("button",{name:"Sign in",exact:true}).click();await page.getByRole("button",{name:"Use a recovery code"}).click();
 await page.getByLabel("Recovery code",{exact:true}).fill("a".repeat(22));await accessible(page);
 await page.getByRole("button",{name:"Continue",exact:true}).click();await expect(page.getByRole("heading",{name:"Overview",exact:true})).toBeVisible();await accessible(page);
 expect(unexpected).toEqual([]);
});
test("security settings and recovery-code overlay support keyboard use",async({page})=>{
 const unexpected=await fixtures(page,true);await page.goto("/settings");
 await expect(page.getByRole("button",{name:"Remove",exact:true})).toBeVisible();await accessible(page);
 await page.getByRole("button",{name:"Replace recovery codes"}).click();await expect(page.getByRole("dialog")).toBeVisible();await accessible(page);
 await page.getByLabel("Current password").fill("Anonymous browser passphrase");await page.getByLabel("Authenticator or recovery code",{exact:true}).fill("123456");
 await page.getByRole("button",{name:"Confirm",exact:true}).click();await expect(page.getByText("anonymous-fixture-code-01")).toBeVisible();await accessible(page);
 await page.getByLabel("I have saved these codes securely").check();await page.getByRole("button",{name:"Done",exact:true}).click();await expect(page.getByRole("dialog")).toHaveCount(0);
 await page.getByRole("button",{name:"Change password",exact:true}).click();await page.keyboard.press("Escape");await expect(page.getByRole("dialog")).toHaveCount(0);
 expect(unexpected).toEqual([]);
});
test("member creation drawer or sheet is accessible and closes outside",async({page})=>{
 const unexpected=await fixtures(page,true);await page.goto("/members");await expect(page.getByRole("heading",{name:"Members",exact:true})).toBeVisible();await accessible(page);
 await page.getByRole("button",{name:"Add member",exact:true}).click();await expect(page.getByRole("dialog")).toBeVisible();await accessible(page);
 await page.locator(".overlay-backdrop").click({position:{x:2,y:2}});await expect(page.getByRole("dialog")).toHaveCount(0);expect(unexpected).toEqual([]);
});
