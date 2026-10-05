import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
test('organization policies, backup information and retention preview remain accessible in both themes',async({page})=>{
 const unexpected=[];
 const name='Synthetic Community Credit Union with a Longer Organization Name';
 const responses={
  '/api/v1/auth/me':{userId:'synthetic-settings-admin',email:'settings@invalid.example',permissions:['OWN_DATA_READ','SETTINGS_WRITE','MEMBER_WRITE','USER_ADMIN','APPROVE'],preferredSignInMethod:'TOTP'},
  '/api/v1/organization/branding':{organisationName:name,primaryColour:'#083a4f',currencyDisplay:'SYMBOL',logoDataUrl:'',passwordMinLength:16},
  '/api/v1/organization':{passwordMinLength:16,sessionIdleMinutes:15,primaryColour:'#083a4f',currencyDisplay:'SYMBOL',logoDataUrl:''},
  '/api/v1/organization/operations':{receiptStorage:'local',scannerConfigured:false,emailEnabled:false,backupRetention:'30 daily / 12 monthly',backupVerification:'Managed by the operator; synthetic configuration does not prove backup freshness.'},
  '/api/v1/payments/policy':{organisationName:name,contactEmail:null,contactPhone:null,requiredApprovals:1,feeBearer:'UNION',paymentMin:'1.00',paymentMax:'1000.00'},
  '/api/v1/settings/rollover':{defaultRolloverPolicy:'NO_ROLLOVER'},
  '/api/v1/auth/security':{totpEnabled:true,recoveryCodesRemaining:8,passkeys:[]},
  '/api/v1/auth/google/config':{enabled:false},
  '/api/v1/retention':{retentionYears:7,candidates:[{memberId:'synthetic-former-member',memberNumber:'SYN-001',leftOn:'2016-01-01',eligibleOn:'2023-01-01',version:0}],unconfirmedDepartures:[],pending:[],pendingReceiptDeletions:0},
  '/api/v1/retention/members/synthetic-former-member/preview':{memberId:'synthetic-former-member',memberNumber:'SYN-001',leftOn:'2016-01-01',eligibleOn:'2023-01-01',sourceFingerprint:'synthetic-fingerprint',privateImportRows:0,receipts:0,consequence:'Remove private details and preserve financial totals.'}
 };
 await page.route('**/api/v1/**',async route=>{const path=new URL(route.request().url()).pathname;const body=responses[path];if(body===undefined)unexpected.push(path);await route.fulfill({status:body===undefined?404:200,contentType:'application/json',body:JSON.stringify(body??{message:'Unexpected synthetic fixture request'})});});
 await page.goto('/settings');
 await expect(page.getByRole('heading',{name:'Branding and account policies'})).toBeVisible();
 await expect(page.getByRole('heading',{name:'Storage and backups'})).toBeVisible();
 await expect(page.getByRole('button',{name:'Preview anonymization'})).toBeVisible();
 async function accessible(){const results=await new AxeBuilder({page}).withTags(['wcag2a','wcag2aa','wcag21a','wcag21aa']).analyze();expect(results.violations.map(({id,nodes})=>({id,nodes:nodes.map(n=>n.target)}))).toEqual([]);expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);}
 await accessible();
 await page.getByRole('button',{name:'Preview anonymization'}).click();await expect(page.getByRole('dialog')).toBeVisible();await accessible();
 await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);expect(unexpected).toEqual([]);
});
