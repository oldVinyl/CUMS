import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
test('settlement creation and independent review use accessible drawer or sheet',async({page})=>{
 const today=new Date().toISOString().slice(0,10),errors=[],requests=[];
 page.on('pageerror',error=>errors.push(error.message));
 let checker=false,pending=false;
 const payment={id:'synthetic-payment',reference:'SYNTHETIC-SETTLEMENT-001',gross:'100.0000',fee:'2.5000',expectedNet:'97.5000',confirmedDate:today,settledAmount:null,settledDate:null,sourceFingerprint:'synthetic-source',pending:false};
 await page.route('**/api/v1/**',async route=>{
  if (new URL(route.request().url()).pathname === '/api/v1/organization/branding') {
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({organisationName:'Harbour Credit Union',primaryColour:'#083a4f',currencyDisplay:'SYMBOL',logoDataUrl:'',passwordMinLength:12})}); return;
  }
  const request=route.request(),path=new URL(request.url()).pathname;let body,status=200;
  if(path==='/api/v1/auth/me')body={userId:checker?'checker':'maker',email:'synthetic@example.invalid',preferredSignInMethod:'TOTP',permissions:['OWN_DATA_READ','DASHBOARD_READ_ALL','RECONCILE','APPROVE']};
  else if(path==='/api/v1/reconciliations/preview')body={equity:'100',principal:'100',income:'0',integrityDifference:'0',invested:'0',expectedCash:'100',sourceFingerprint:'synthetic-figures'};
  else if(path==='/api/v1/reconciliations')body={records:[],hasNext:false};
  else if(path==='/api/v1/reconciliations/settlements')body={payments:[{...payment,pending}],matches:pending?[{id:'synthetic-match',paymentId:payment.id,reference:payment.reference,amount:'96.50',settledDate:today,notes:'Synthetic bank statement; one GHS discrepancy.',makerId:'maker',requestId:'synthetic-request',status:'PENDING',approvals:0,requiredApprovals:1}]:[],hasNext:false};
  else if(path==='/api/v1/reconciliations/settlements/payments/synthetic-payment'&&request.method()==='POST'){requests.push(request.postDataJSON());pending=true;body={id:'synthetic-match'};}
  else {status=404;body={message:'Unexpected synthetic fixture route'};errors.push(path);}
  await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
 });
 await page.goto('/reconciliation');await expect(page.getByText('Awaiting bank settlement match')).toBeVisible();
 await page.getByRole('button',{name:'Match bank settlement',exact:true}).click();const dialog=page.getByRole('dialog');await expect(dialog).toBeVisible();
 await dialog.getByLabel('Bank settlement amount (GHS)').fill('96.50');await dialog.getByLabel('Bank evidence and explanation').fill('Synthetic bank statement; one GHS discrepancy.');
 expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
 await dialog.getByRole('button',{name:'Submit settlement match'}).click();await expect(dialog).toHaveCount(0);expect(requests).toEqual([{amount:'96.50',settledDate:today,notes:'Synthetic bank statement; one GHS discrepancy.',sourceFingerprint:'synthetic-source'}]);
 checker=true;await page.reload();await page.getByRole('button',{name:'Review settlement match'}).click();await expect(page.getByRole('button',{name:'Confirm settlement match'})).toBeVisible();
 expect((await new AxeBuilder({page}).analyze()).violations).toEqual([]);
 await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);expect(errors).toEqual([]);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});
