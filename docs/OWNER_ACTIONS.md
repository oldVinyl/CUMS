# Owner setup and release handoff

This is the central list of steps that need the owner, client or deployment operator. Main-build implementation is followed by the isolated test suite; passing builds alone does not establish financial correctness or production readiness. Never paste secrets, member identities, private workbook contents or real receipts into a chat or CI artifact.

## 1. Union identity and policy

- [ ] Confirm the organization name, public logo and union contact details. In Settings → Organization policies, save those details; upload a PNG up to 512 KB / 2048 × 2048. The logo/name are intentionally public on the landing page.
- [ ] Review required independent approvals, fee bearer, online limits, rollover, password length, idle timeout and personal-data retention. Settings groups these policies in one organization block. The app enforces a 12-character password floor and cannot remove maker/checker separation.
- [ ] Confirm the data-protection contact, breach procedure and lawful retention policy with the union's adviser. Review PRIVACY.md. Arrange retention of original workbooks, email copies, earlier backups, database recovery logs and object versions separately. Application anonymization cannot erase external copies.

Expected result: public identity is correct, policies are saved/audited, and an authorized owner has accepted them.

## 2. Google sign-in (optional until configured)

- [ ] Create a Google Cloud OAuth **Web application** client and consent configuration for the union. Register the exact JavaScript origin `http://localhost:5173` for local use and `https://<domain>` for production.
- [ ] Set `APP_GOOGLE_CLIENT_ID` in the server environment and restart the backend. This integration verifies Google Identity Services ID tokens; it does not use a server callback/client secret. Do not invent a redirect URI for a different OAuth flow.
- [ ] Verify first sign-in setup, explicit linking, nonce/replay rejection and sign-in using a union-invited eligible account. TOTP and passkeys remain available to everyone while Google is gated.

Expected result: the Google option becomes available only for a configured client and does not create arbitrary public accounts. See SETUP.md, Account security in USER_GUIDE.md.

## 3. Payments

- [ ] Obtain union-owned Paystack test credentials; use `PAYMENT_PROVIDER=paystack` and `PAYSTACK_SECRET_KEY` on the backend. Never expose the secret to frontend configuration.
- [ ] Register the application's actual webhook URL: `https://<domain>/api/v1/payments/webhook`. For local acceptance use the tunnel procedure in SETUP.md; keep real member information out of the test environment.
- [ ] Enable the provider's Ghana mobile-money channel and verify hosted card/MoMo payment, its provider prompt, confirmation, amount/reference/currency matching, retries and exact-once ledger credit with synthetic accounts.
- [ ] Obtain actual bank settlement evidence and complete an independently reviewed match in Reconciliation. A match records bank evidence; it does not make a bank payout or credit the contribution again.
- [ ] Complete Paystack business/settlement verification before substituting live credentials. Update the union's manual bank/wallet instructions in Payments and inspect them as an ordinary member.

Expected result: test provider and settlement checks pass before live payment activation. The dev FakeProvider is never installed in production. See SETUP.md and USER_GUIDE.md.

## 4. Email — previously postponed

- [ ] When ready, provide SMTP_HOST, SMTP_PORT, SMTP_FROM, SMTP_USER and SMTP_PASSWORD. Set APP_MAIL_ENABLED=true; production requires SMTP_STARTTLS=true.
- [ ] For local-only email acceptance, MailHog can be retried using the existing development Compose service. It was postponed at the owner's request; it is not necessary for local non-email work.
- [ ] Verify invitation, password-reset, fully lost-factor recovery, payment/approval notifications and failed-message retry through the configured service. Confirm consumed/expired links are not delivered again.

Expected result: a synthetic recipient receives the correct single-use message, and Accounts → Email delivery records the outcome. The encrypted outbox already queues eligible messages while delivery is disabled. See SETUP.md and RUNBOOK.md.

## 5. Private source migration

- [ ] Work only with the local private workbook/staging files described in LEGACY_IMPORT.md. Do not upload the original workbook to Git, CI, a model, third-party conversion or screenshots.
- [ ] Confirm the as-at year/date, joining dates and investment purchase/maturity dates or sign off explicit assumptions where the client kept no dates. Historical income is treated as received according to the owner's instruction; document that assumption for the independent checker.
- [ ] Reconcile every source row and totals locally. Use member numbers/stable local mappings to avoid mismatching the two workbooks. Collect missing member contact/identity details later through authorized editing.
- [ ] Use a fresh target database without dev mock entries. Import members, opening balances/contributions and one historical investment/allocation file per cycle, oldest first. **Never import earned income both as an opening balance and again as received investment income.**
- [ ] Have a different eligible union officer review and approve the dry-run source and dates. The dev account's full access does not permit it to approve its own money requests.
- [ ] Compare the dashboard, statements and allocation totals with the private workbook, then run one actual cycle in parallel with Excel before accepting migration.

Expected result: independently approved batches and reconciled ledger totals. Original entries remain immutable; later corrections use reviewed compensations/reposts, not direct SQL overwrites. See LEGACY_IMPORT.md and historical sections in USER_GUIDE.md.

## 6. Production host and scanner

- [ ] Supply a domain, DNS, TLS contact and Linux deployment host. Allow memory for PostgreSQL, the backend and scanner; the scanner container has a 4 GB limit, so an 8 GB host is a practical starting point subject to actual workload.
- [ ] Copy deploy/.env.production.example to root .env.production, restrict access, and create independent random APP_ENCRYPTION_KEY/AUTH_THROTTLE_KEY plus a unique DB_PASSWORD. Keep recoverable key copies outside the host and backups.
- [ ] Follow DEPLOYMENT.md: Compose configuration/build, fresh leadership bootstrap for distinct ADMIN/PRESIDENT people, HTTPS start and current permissions. Do not enable dev samples/bootstrap in production.
- [ ] Production Compose includes a private ClamAV service with persistent signatures and health checks. CLAMAV_HOST defaults to `scanner`; production startup rejects a blank scanner host. Wait for signature download and a healthy scanner. Port 3310 is not published to the host/internet.
- [ ] Verify clean synthetic uploads, rejected malware fixtures and fail-closed scanner outages in the isolated acceptance environment. Inspect HTTPS/CSP/cookies and non-public API/storage access on the real domain.

Expected result: healthy private services, HTTPS, working passkeys for the production RP host, scanner enforcement and no public object/DB/daemon access. The scanner needs internet egress for signature updates, not public ingress. [ClamAV Docker documentation](https://docs.clamav.net/manual/Installing/Docker.html) explains signature volumes, daemon health checks and its RAM requirements.

## 7. Storage, off-site backups and recovery

- [ ] Provision private receipt storage (local volume or private S3-compatible bucket/prefix) and a separate off-site encrypted backup destination. Configure the variables and least-privilege identities in DEPLOYMENT.md; block public access and use TLS/encryption.
- [ ] Keep the age private recovery identity independently offline. Put only AGE_RECIPIENT on the server. Schedule deploy/backup-host.sh nightly and alert on failure or stale backups.
- [ ] Verify 30 daily / 12 monthly retention and an actual download/restore into a separate empty target. Record restored totals, receipt integrity, key recovery, duration and owner acceptance. Never run a drill over a populated deployment.
- [ ] Apply later approved anonymization decisions before making an older restored backup available to users. Review object-version and older-backup retention separately.

Expected result: independently recoverable encrypted off-site data and files, with an operator-recorded drill. Settings → Storage and backups shows configuration, not a claim that a fresh backup or successful restore exists.

## 8. Repository, dependency audit and final acceptance

- [ ] Confirm the private GitHub repository/organization, remote and access. Supply an NVD API key as the GitHub Actions secret NVD_API_KEY under repository Settings → Secrets and variables → Actions. Do not put it in source control.
- [ ] Run the existing hosted CI/dependency/secret gates after the local synthetic suite. Hosted CI status cannot be established without a repository run and its external credentials.
- [ ] Review CHECKLIST.md and SECURITY_CHECKLIST.md, record owner financial/privacy/restore acceptance, then authorize deployment and release/tag. Configuration being present does not prove external delivery.

Expected result: a recorded green run and accepted production handoff. Generic implementation continuations are not a substitute for these owner inputs.
