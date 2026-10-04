import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const today = new Date().toISOString().slice(0,10);
const balance = {principal:'0',income:'0',equity:'0'};
const permissions = ['OWN_DATA_READ','MEMBER_READ','MEMBER_WRITE','DASHBOARD_READ_ALL','LEDGER_READ','LEDGER_POST',
 'LEDGER_REVERSE','LEDGER_BACKDATE','FISCAL_YEAR_CLOSE','PERIOD_UNLOCK','APPROVE','INVESTMENT_READ','INVESTMENT_WRITE',
 'REPORT_EXPORT','SETTINGS_WRITE','PAYMENT_VERIFY','RECONCILE','USER_ADMIN','ROLE_ASSIGN','MEMBER_IMPORT','AUDIT_READ'];

async function fixtures(page) {
 const unexpected=[];
 const responses={
  '/api/v1/auth/me':{userId:'anonymous-operator',email:'operator@example.invalid',permissions,preferredSignInMethod:'TOTP'},
  '/api/v1/members':{items:[],page:0,size:25,hasNext:false},
  '/api/v1/investments':[], '/api/v1/investments/approvals/pending':[],
  '/api/v1/settings/rollover':{defaultRolloverPolicy:'ROLL_PRINCIPAL_AND_INCOME'},
  '/api/v1/ledger':{entries:[],page:0,hasNext:false}, '/api/v1/ledger/approvals':[],
  '/api/v1/ledger/fiscal-years':[{year:Number(today.slice(0,4)),status:'OPEN'}],
  '/api/v1/payments':{payments:[],page:0,hasNext:false},
  '/api/v1/payments/methods':[{id:'anonymous-method',name:'Fixture bank transfer',instructions:'Synthetic account only. Use your reference.',active:true}],
  '/api/v1/payments/own-member':{memberId:'anonymous-member'},
  '/api/v1/payments/online/config':{provider:'FAKE',enabled:true,development:true},
  '/api/v1/reconciliations/preview':{equity:'0',principal:'0',income:'0',integrityDifference:'0',invested:'0',expectedCash:'0',sourceFingerprint:'anonymous'},
  '/api/v1/reconciliations':{records:[],page:0,hasNext:false},
  '/api/v1/account-recovery':[],
  '/api/v1/notifications':{items:[],page:0,hasNext:false},
  '/api/v1/users':{users:[],page:0,hasNext:false}, '/api/v1/users/roles/pending':[],
  '/api/v1/imports':{batches:[],page:0,hasNext:false},
  '/api/v1/audit':{events:[{id:'anonymous-audit-event',actorId:null,actorEmail:null,action:'FILE_DOWNLOADED',entityType:'ATTACHMENT',entityId:null,occurredAt:`${today}T12:00:00Z`,requestId:null,requestIp:'2001:db8::7'}],page:0,hasNext:false},
  '/api/v1/portal':{memberId:'anonymous-member',memberNumber:'FIX-001',fullName:'Anonymous fixture',asAt:today,balanceBf:balance,ytd:balance,total:balance,roePercent:'0',equityRatioPercent:'0',trend:[],allocations:[]},
  '/api/v1/portal/ledger':{entries:[],page:0,hasNext:false},
  '/api/v1/portal/profile':{version:0,email:null,phone:null,nextOfKinName:null,nextOfKinPhone:null,nextOfKinRelationship:null},
 };
 await page.route('**/api/v1/**',async route=>{
  const request=route.request(), path=new URL(request.url()).pathname;
  let body=responses[path],status=200;
  if(request.method()!=='GET'||body===undefined){unexpected.push(`${request.method()} ${path}`);status=404;body={message:'Unmapped isolated fixture request'};}
  await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
 });
 return unexpected;
}

for(const [path,title] of [['/ledger','Ledger'],['/investments','Investments'],['/payments','Payments'],
 ['/reconciliation','Reconciliation'],['/portal','My membership'],['/reports','Reports'],
 ['/accounts','Accounts and roles'],['/imports','Imports'],['/audit','Audit log'],['/approvals','Approvals']]){
 test(`${title} empty states and operational controls are accessible`,async({page},testInfo)=>{
  const unexpected=await fixtures(page);await page.goto(path);
  await expect(page.getByRole('heading',{name:title,exact:true})).toBeVisible();
  await expect(page.getByText(/Loading (ledger|investments|payments|accounts|imports|audit records|membership|reconciliation|approvals)/)).toHaveCount(0);
  expect(unexpected).toEqual([]);
  const result=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();
  expect(result.violations.map(({id,nodes})=>({id,nodes:nodes.map(node=>node.target)}))).toEqual([]);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  expect(unexpected).toEqual([]);
  if(testInfo.project.name==='1440-light') {
   const directory=fileURLToPath(new URL('../../docs/screenshots/',import.meta.url));
   await mkdir(directory,{recursive:true});
   await page.screenshot({path:`${directory}/${path.slice(1)}.png`,fullPage:true});
  }
 });
}
