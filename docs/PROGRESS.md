# Progress

Check implemented items after their recorded verification. The owner subsequently authorized tests: the local regression and focused workflow results are recorded in TEST_RESULTS.md. Release acceptance remains separate.

## Phase 0 — Setup

- [x] Initialize Git and `.gitignore`
- [x] Add README and initial SETUP, ARCHITECTURE, PROGRESS, ASSUMPTIONS, OPEN_QUESTIONS
- [x] Docker Compose development services defined and config validated
- [x] Spring Boot skeleton, health endpoint, unit smoke test
- [x] React/Vite skeleton
- [x] CI workflow, gitleaks configuration, ESLint
- [x] Start PostgreSQL 16 and verify API health and protected-route denial against it
- [ ] Confirm MailHog starts (deferred at the owner's request after repeated Docker Hub pull failures; revisit before email notifications and Phase 8 acceptance)
- [x] Testcontainers PostgreSQL 16 smoke: all migrations, schema validation and immutable exact ledger posting in a disposable container
- [x] Java Spotless/google-java-format and frontend Prettier configuration; new workflows formatted and checked

## Phase 1 — Foundation

- [x] V22–V28 revision history, payments/proofs/jobs, reconciliation, policies, notification outbox and invitation migrations verified
- [x] Signed-in navigation follows current profile permissions; sign-in lands on the Overview route, with own membership figures for ordinary members
- [x] Hide restricted menu links before profile load; guard signed-in direct routes and refresh permissions on focus/once per minute; dev retains all known permissions
- [x] Flyway V1–V21 core financial, role, approval, fiscal snapshot, authentication, member identity, Google link, passkey, investment allocation, maturity, rollover, settings, and admin investment-read migrations tested against PostgreSQL 16
- [x] Remaining implemented workflow tables through V43: reviewed investment imports, retention controls, archived economic corrections, organization security and financial replay records (new migrations verified by application startup; tests skipped at owner request)
- [x] Structured API validation and access errors with trace IDs
- [x] Append-only ledger and audit triggers with database tests
- [x] AES-256-GCM private-field encryption, versioned keys, and blind indexes with tests
- [x] Data-minimal append-only audit service and authentication security events
- [x] Sensitive member-detail reads audited without storing private values in the event
- [x] Audit report/statement generation and downloads without recording private report contents
- [x] Permission-protected audit metadata screen with date/action/entity/actor filters, stable pagination, and audited formula-safe CSV export
- [x] Request IP/trace/actor context, transactional financial before/after records and failed-password audit acceptance
- [ ] Complete historical audit payload review and log-scrubbing acceptance
- [x] Argon2id password hashing and basic common-password policy with tests
- [x] Session, login-attempt, and TOTP recovery-code tables
- [x] Opaque access/refresh/CSRF token service with hashed storage, rotation, replay revocation, and tests
- [x] RFC 6238 TOTP code generation and adjacent-step verification with standard test vectors
- [x] Database-backed login throttling and exponential account lockout with keyed identifier digests
- [x] Password first-factor verification with generic failures and tests
- [x] Password-change and TOTP challenges with encrypted enrollment secrets, attempt limits, replay protection, and integration tests
- [x] One-time recovery codes issued at TOTP enrollment, stored as hashes, and accepted once for account recovery
- [x] HTTP sign-in, refresh, logout, and profile endpoints with strict cookies, origin and CSRF checks, and integration tests
- [x] Account-wide session revocation and authenticated sign-out-on-all-devices action
- [x] Bucket4j per-IP and global auth POST limit with HTTP 429 feedback and tests
- [x] Recovery-code regeneration and permission/ownership enforcement on implemented authenticated routes
- [x] PostgreSQL-backed global/per-IP greedy authentication limits shared across instances, digest-only addresses and concurrency/refill/expiry tests
- [ ] Complete route permission sweep acceptance
- [x] Seed six roles and permission grants; reject overlapping active assignments
- [x] Read live permissions on each check; approved role changes take effect immediately
- [x] Give the configured local bootstrap account every known permission only under the `dev` profile, without weakening production ADMIN grants or maker-checker separation
- [x] Block revocation of the last active ADMIN or PRESIDENT through role-change approvals
- [x] Enforce current role permissions and session ownership on member API endpoints
- [x] Guard ADMIN/PRESIDENT assignments against automatic expiry and block deactivation of linked leadership accounts until approved role removal
- [x] Responsive frontend shell, light/dark theming, money formatter, feedback toasts, and Vinette credit
- [x] Server-connected sign-in, password-change, and TOTP screens with toast feedback
- [x] Scannable TOTP setup QR with a manual key fallback
- [x] Google ID token verification, one-use nonce, explicit account linking, and gated UI for configured client IDs
- [x] WebAuthn passkey registration and assertion services with stored keys, one-use ceremonies, and user verification
- [x] Browser passkey registration, sign-in, preference, and replay rejection with a virtual authenticator against a disposable PostgreSQL database
- [x] Test lost-device recovery using a remaining protected factor/recovery code and verified own-passkey removal
- [x] Require every account to choose a protected sign-in method on first sign-in
- [x] React Hot Toast themes for light/dark success, information, and error feedback with multiline detail and promise feedback for member mutations
- [x] Offer TOTP, passkey, and gated Google sign-in to every account, including first sign-in preference setup
- [x] Signed-in member directory and creation form use live API with role-aware controls and toast feedback
- [x] Financial workspace routes use live APIs and permission-aware navigation; signed-out demonstration remains clearly labelled

## Phase 2 — Members and ledger

- [x] Member create, list, and private detail read with encrypted fields, blind indexes, permission checks, CSRF, and integration tests
- [x] Versioned member editing with encrypted-field replacement, stale-save conflict response, audit event, and tests
- [x] Full create and edit member forms with validation controls and success/error toasts
- [x] Generate short unique member numbers in the backend; rotate the add-member icon into a close icon
- [x] Show audited live approved member balances as at the selected date
- [x] Permission-aware member directory search by name or number with bounded server pagination and navigation
- [x] Versioned member deactivation/reactivation with linked portal-account disable, session revocation, audit events, confirmation UI, and tests
- [x] Transactional maker-checker approval service with distinct approvers and atomic action execution
- [x] Approved current-day seed/addition posting to the immutable ledger
- [x] Approved current-day manual contribution reversal with exact negative amount and reason
- [x] Member balances computed from ledger entries through an as-at date
- [x] Approved year-end close with balance-forward snapshots and fiscal-year locking
- [x] Historical contribution impact preview with independently approved future-only posting or chronological cycle reverse/repost, checksum-bound source and snapshot recomputation
- [x] Historical receipt posting uses original paid dates, impact previews, future-only/repost choices, unique receipt-to-ledger links and separate checker review
- [x] Reconstruct archived cycle dates, allocations and actual proceeds from immutable revision snapshots for authorized inspection
- [x] Chronological correction across moved/reversed purchase-date revisions using original economic segments, linked income adjustments and offsets at approved compensation dates
- [x] Independently approved, reasoned period unlock with current permission rechecks, duplicate-request prevention, maker cancellation and rejection
- [x] Append-only snapshot revision history; recompute the corrected year and every later snapshot on unlock, approved import and reclose
- [x] Permit reviewed historical ledger imports into open or approved-unlocked years, while blocking changes to posted or archived investment ratios
- [x] Fiscal-year balance-forward inspection with exact component reconciliation, revision numbers, pagination and empty/error states
- [x] Live paginated ledger UI, contribution/reversal/year-close request controls, maker cancellation, confirmation-based approvals, origin/CSRF and permission tests
- [x] Recheck committed investment funds before approving manual contribution compensation
- [x] JPEG/PNG/PDF magic-byte validation, metadata normalization, private storage and authorized audited downloads
- [x] Local HEIC-to-JPEG receipt conversion with bounded dimensions, worker timeout and strict-CSP production acceptance
- [ ] Real private scanner acceptance

## Phase 3 — Investments

- [x] Pesewa allocation engine and property tests
- [x] Persist investment drafts with proposed participation from live uninvested balances, exact allocation shares, and a Sheet A style preview
- [x] Allow the draft creator to edit participation with a recorded reason, including zero to exclude a member; recalculate shares and prevent edits while approval is pending
- [x] Submit drafts for distinct-maker approval and recheck available funds before posting; show pending decisions in the live Investments page
- [x] Approve actual maturity proceeds, preview member impact, and post exact positive or negative income to the immutable ledger
- [x] Give ADMIN read-only access to investments while preserving financial posting and approval restrictions
- [x] Show a reconciled Sheet A totals row and verify the 12-member sheet fixture in the engine and screen
- [x] Create principal-only or principal-and-income rollover drafts from matured investments, with source caps, distinct approval, and live uninvested-fund rechecks
- [x] Allow the creator to discard an unused draft with an audit reason, releasing its rollover source
- [x] Let a maker cancel their pending investment request before editing or discarding its draft
- [x] Approve settlement without rollover and leave member balances uninvested
- [x] Audit an ADMIN/PRESIDENT-managed organisation rollover default and apply it to new drafts
- [x] Current-day investment lifecycle, rollover, settlement and independently approved compensating reversal
- [x] Approved current-day investment reversal with exact gain/loss compensation, immutable revision history, dependency checks, return to draft and fresh posting approval
- [x] Approved fiscal-period unlock and immutable snapshot recomputation
- [x] Correct historical received investment proceeds at original economic dates, compensate prior income, chronologically repost affected current cycles and recompute snapshots (build-verified; tests skipped)
- [x] Sheet A xlsx/PDF downloads with grouped headings, reconciled supplied fixture totals, permissions, audit and visual checks

## Phase 4 — Dashboards and exports

- [x] Persist balance-forward snapshots at approved year-end close
- [x] Serve an authorized live dashboard for any as-at date with balance forward, year-to-date, totals, ROE, equity ratios, and member search
- [x] Restore overview summary cards, add monthly ledger equity trends, period views and sorting, and replace the wide table with compact desktop rows and expandable mobile cards
- [x] Verify overview empty and error states, light/dark themes, and desktop/mobile layouts without page overflow
- [x] Provide opt-in, idempotent fictitious member and ledger samples scoped to the dev profile
- [x] Group organization policies, limits, approvals, fees and rollover in one block; keep personal settings in their own cards and adapt the Vinette credit for smaller screens
- [x] Shared pagination across members, ledger, payments, portal, reconciliation, accounts, audit and import previews/history
- [x] Unify uppercase eyebrow subtitles, themed surfaces/borders/text/buttons and readable desktop/tablet typography
- [x] Verify the supplied Sheet B aggregate totals, ROE/equity-share examples, and exclusion of future effective entries with an independent ledger fixture
- [x] Explicit fiscal-year selector and closed-snapshot reconciliation for the as-at dashboard, including missing members and offsetting principal/income mismatches
- [x] Sheet B xlsx/PDF downloads with grouped headings, numeric totals, audit and visual checks
- [x] Member statements with opening/running/closing balances, date range, PDF/xlsx, staff authorization and own-account IDOR tests
- [x] Live Reports page and statement downloads in member details
- [x] Ledger and public-field member-directory Excel/PDF exports with range/date filters and permission tests
- [x] Check new financial forms and review screens at desktop/mobile widths in both themes, including search styling and secondary-action contrast
- [ ] Complete original Sheet B row-by-row workbook acceptance and snapshot reconciliation

## Phase 5 — Payments

- [x] Provider interface, dev-only simulator and credential-gated Paystack hosted checkout adapter
- [x] Persist references before provider calls; verify member/reference/currency/amount; exact-once ledger credit and separate fee policy
- [x] Raw-body HMAC-SHA512 webhook verification, durable jobs, retries and tampering tests
- [x] Configurable manual instructions and unique pre-transfer references
- [x] Private JPEG/PNG/plain-PDF receipts, byte/size checks, metadata removal, ownership and audited downloads
- [x] Idempotent submission, duplicate warnings, actual verification values, independent approval/rejection/cancellation
- [x] Own payment history and confirmation PDFs with IDOR tests
- [x] Live payment screen, bounded status polling and clearly labelled development simulation
- [x] Persistent payment/approval/invitation email outbox and opt-in SMTP Notifier
- [x] Configurable 1–3 approvals, online limits and captured fee policy in the organization Settings block
- [ ] Real Paystack sandbox acceptance, direct MoMo prompts, settlement matching and operational exception review
- [x] Local HEIC conversion and bounded private malware scan hook with protocol tests
- [x] Receipt replacements retain original proofs/history, return for review and reject stale/verified/posted changes
- [x] Historical manual-payment posting with reviewed original-date/future-only impact
- [ ] Real private scanner acceptance
- [x] Permission-protected notification administration with filters/pagination, failed-message retry confirmation, private-payload exclusion and before/after audit
- [x] Prevent expired/consumed invitation delivery and notifications for ineligible accounts
- [ ] MailHog/SMTP delivery acceptance (still deferred)

## Phase 6 — Member portal

- [x] Hashed expiring single-use invitations, encrypted queued links, replacement invalidation and password activation
- [x] Own balances, BF/YTD/Total, ROE/share, monthly trend, allocations and paginated transactions
- [x] Own statements and encrypted versioned contact/next-of-kin editing
- [x] Server-derived membership and own-data/receipt/statement IDOR tests
- [x] Accounts/roles UI with invitations, search/pagination and reviewed independent role decisions
- [x] ADMIN/PRESIDENT grants require explicit approved revocation rather than automatic expiry
- [x] Dev-only linked sample membership and initially disabled fictitious reviewer for separate-account testing
- [x] Own JSON profile/account/ledger/payment/allocation/revision/snapshot/receipt-metadata export, exact decimal strings, audited private download and ownership/secret-exclusion tests
- [x] Password/reset and independently reviewed fully lost-factor recovery administration
- [ ] Live invitation and recovery email delivery acceptance

## Phase 7 — Import and reconciliation

- [x] Dry-run importer, approval and batch reversal
- [x] Bounded UTF-8 CSV/single-sheet XLSX member and ledger imports, downloadable templates, row validation and source totals reconciliation
- [x] Dry run writes no records; confirmation binds the exact file checksum and encrypts staged private rows
- [x] Independently approve entire batches atomically; stale data, permissions, closed periods and duplicate references are rechecked
- [x] Reviewed exact ledger batch compensation and unused-member deactivation, retaining original source and financial records
- [x] Live import validation/review/history screens, pagination, CSRF/origin/permission and rollback tests
- [x] Historical investment/allocation imports, exact supplied-share validation and separate reviewed correction of dependent investment cycles
- [x] Current-day ledger/holdings/cash comparison with exact differences and immutable snapshots
- [x] Separate finalization/rejection/cancellation with source freshness and transaction rollback tests
- [x] Live reconciliation screen and paginated decision history
- [x] Historical reconstruction from immutable revision evidence and approved economic corrections
- [x] Reviewed gateway/bank settlement matching with amounts, dates, fee/net differences, corrections and independent approval

## Phase 8 — Delivery

- [x] 60 isolated responsive checks for payments, portal, reconciliation, accounts, invitations and settings at 320/375/768/1024/1440 in both themes
- [x] PWA manifest/icon and production-only public-static service worker; private API/documents excluded
- [x] Responsive Playwright/axe regression for implemented flows and public-static-only PWA cache
- [ ] Final full security review and remaining financial-action browser acceptance
- [ ] Production deployment, backups and restore drill
- [x] User guide, runbook, privacy notes and release checklist maintained
- [ ] Final release acceptance; tests for new workflows were waived for this implementation pass
- [x] User guide, runbook, privacy implementation notes and release checklist drafts

## Latest verified pass — 2026-10-03

- [x] Complete backend suite: 138 tests, no failures/errors/skips, including 250 generated financial allocation cases
- [x] Frontend regression suite: 39 tests; production build and lint; four focused overview tests rechecked after the final header layout adjustment
- [x] Ten additional audit responsive checks at 320/375/768/1024/1440 in both themes; 70 checks across the new operational screens
- [x] Recheck all 70 operational layouts after theme/type/settings/pagination changes and add ten import review checks; 80 checks total
- [x] Thirty further checks for fiscal overview/reconciliation, ledger correction/snapshot forms and member data export at 320/375/768/1024/1440 in both themes
- [x] Apply V30 snapshot-history migration to the local development backend and verify application health
- [x] Import, historical-correction, audit and recovery implementation completed in subsequent checkpoints
- [ ] Production delivery and external acceptance remain open; implementation completion does not certify a production release.

## Deferred follow-ups

- [ ] MailHog startup and live email delivery remain deferred at the owner's request; the persistent outbox and gated SMTP implementation are built.
- [ ] Google sign-in stays gated until OAuth credentials are supplied.
- [x] Own-passkey removal, last-factor protection, recovery codes/spare keys and independently authorized fully lost-factor recovery

## Workspace usability fixes — 2026-10-03

- [x] Signed-out pages use the full content width without workspace navigation; successful sign-in redirects to Overview
- [x] Profile menu includes permitted membership/settings links and explicit sign-out; theme toggle remains visible on mobile
- [x] Permission-filtered Financial operations and Administration groups reduce the main navigation length; dev retains every permission
- [x] Shared focus-trapped desktop side drawer and draggable mobile/tablet bottom sheet, outside-click/Escape dismissal and opener focus restoration
- [x] Member details, add member, investment/rollover drafts, contribution entry and receipt submissions use the shared overlay
- [x] Debounced member results appear while typing in contributions, payments and reports, with keyboard selection, cancellation, loading/error/empty feedback and selection reset
- [x] Overview and membership equity charts use API ledger balances, labelled dates/currency, a zero baseline for losses and exact balance tables; misleading progress bars removed
- [x] Contact details and member statement precede transactions; only own data export follows the transaction list
- [x] Members can see active union bank/wallet instructions and submit receipts for their server-linked membership; staff verification and independent approval remain enforced
- [x] Email-based invitations support prospective members without preselecting a membership; acceptance generates the unique member number and activates only MEMBER access
- [x] Existing matching memberships link automatically; ambiguous/inactive contact matches, reused/replaced links, revoked inviters and membership changes during the wait are rejected
- [x] V31 applied to local development; restarted backend health reports UP
- [x] Backend suite: 143 tests and 250 generated allocation checks; frontend suite: 49 tests; production build and lint pass
- [x] 80 isolated browser layout checks at 320/375/768/1024/1440 in light/dark themes; drawer/sheet outside dismissal, short/long drag, member receipt permissions, profile menu and signed-out widths verified
- [ ] Live SMTP invitation delivery remains deferred with MailHog; the encrypted outbox queues email for configured delivery
## Legacy workbook preparation and public landing — 2026-10-03

- [x] Inspect source structures, numeric balances and income formulas locally without sending member names/details to tool output
- [x] Ignore the private source workbooks and migration directory in Git
- [x] Prepare 16 memberships and 54 ledger summaries, reconciling BF/YTD components to closing equity 289,666.0000
- [x] Record the owner's assumption that all historical income was received; source maturity/receipt evidence remains unavailable
- [x] Preserve blank unknown contact/identity details, stable legacy member numbers and explicit SOURCE_BASELINE membership dates
- [x] Keep backward compatibility with the original membership import template; validate the new date-basis column
- [x] Label baseline dates in member details/reports/exports and suppress misleading monthly charts when legacy summaries are present
- [x] Replace the public overview with a simple landing page, clear login and responsive Vinette/Vinyl developer credits
- [x] Document fresh-database migration, independent approval, double-counting prevention and reviewed corrections in LEGACY_IMPORT.md
- [x] Verify 144 backend tests, 250 generated allocation cases, 50 frontend tests, three anonymous converter tests, production build and lint
- [x] Verify 16 isolated mobile light/dark operational layouts, drawer/sheet interactions, member permissions and public layouts at 320/375/1440
- [x] Apply V32 to the development database and restart the backend; health is UP
- [ ] Post reviewed private imports into a fresh database without mock entries using a separate authorized checker
- [ ] Collect actual joining/contact/identity details and historical investment evidence; prepared summaries do not reconstruct investment cycles

## Account security and automated browser acceptance — 2026-10-03

- [x] List owned passkeys using opaque management IDs and registration/last-use/backup metadata
- [x] Verify fresh password plus authenticator/recovery code or verified passkey before account security changes
- [x] Revoke lost passkeys, deny subsequent assertions, preserve credential metadata and prevent removal of the last protected method
- [x] Revoke every session/challenge after password changes or passkey removal; repair an unavailable preferred method
- [x] Replace hashed recovery codes atomically, invalidate all old codes and display replacements once without browser persistence
- [x] Persist failed-verification lockouts; prevent TOTP/recovery-code replay and foreign-account credential access
- [x] Add separate themed Settings cards, confirmation drawer/sheet, keyboard/focus behavior and promise feedback
- [x] Use two real virtual authenticators against disposable PostgreSQL to verify registration, login, replay rejection, removed-key/session rejection, spare-key login and last-method protection
- [x] Add Playwright/axe CI job and 40 browser scenarios at 320/375/768/1024/1440 in both themes; intercepted APIs never post to the financial database
- [x] Fix low-contrast mobile navigation, authentication links/buttons and overview badges; direct sign-in supports the selected/OS theme and a toggle
- [x] Verify 152 backend tests plus 250 generated allocation checks, 54 frontend tests, production build and lint
- [x] Apply V33 and restart the local backend; health is UP
- [x] Add SECURITY_CHECKLIST.md and account-security/setup instructions
- [x] Complete recovery when all protected factors/codes are lost and independently authorized administrative reset
- [ ] Live password-reset email delivery acceptance
- [x] Historical workflows and retention anonymization implemented in the functionality completion pass
- [ ] Live dependency/hosted CI and production backup/deployment acceptance; new test execution explicitly waived for this pass

## Production delivery and security pass — 2026-10-03

- [x] Add production Compose with internal PostgreSQL, private receipt volume, read-only backend, bounded memory and Caddy HTTPS/security headers/CSP
- [x] Prevent private uploads/build artifacts from entering Docker build contexts
- [x] Reject unsafe production startup settings, dev samples/bootstrap, fake provider, weak/shared keys, insecure cookies/origin and enabled non-TLS mail
- [x] Explicit atomic first-leadership bootstrap creates distinct ADMIN/PRESIDENT accounts only on a fresh account table, requires new passwords and records minimal audit events
- [x] Encrypt PostgreSQL and private uploads together using age; keep plaintext staging in tmpfs and original keys separate
- [x] Add host backup lock/quiescence/restart, S3-compatible off-site upload and exact-name 30-daily/12-monthly retention
- [x] Refuse restores over existing tables/uploads, validate archive paths/types and restore PostgreSQL in one transaction
- [x] Run disposable synthetic restore drill: ledger total/receipt recovery, wrong-key rejection and populated-target protection
- [x] Verify two retention tests and add the reproducible Docker recovery drill to CI
- [x] V34 enriches all audit inserts with HTTP request IP/trace; tests cover direct writers, IPv4/IPv6, append-only protection and pooled background isolation
- [x] Expose audit IP metadata in authorized screen/CSV; production forwarding trusts only the private gateway
- [x] Add bounded private ClamAV receipt hook, scanning raw and normalized files, with clean/malware/error/unavailable protocol tests
- [x] Add Dependabot, deployment/restore instructions and safe Linux Maven wrapper execution
- [x] Verify 161 backend tests, 250 generated allocation checks, 54 frontend tests, build/lint, production Compose schema and Caddy configuration
- [x] Apply V34 to the local backend and confirm health UP
- [x] Verify 140 Playwright/axe scenarios at five widths in both themes, including operational empty states; generate ten synthetic screenshots and role-specific guide sections
- [ ] Real production host/domain/bucket, live scanner, provider/mail credentials and owner restore/delivery acceptance remain external gates

## Remaining master-prompt completion gates

- [x] Historical contribution impact preview with future-only and dependent-cycle reverse/repost options for current cycle purchase dates
- [x] Historical receipt posting/replacement, moved-cycle reconstruction and investment/allocation imports
- [x] Fully lost-factor recovery and independently authorized reset administration
- [ ] Live password/reset email delivery acceptance (SMTP deferred)
- [x] Independently approved configurable retention/anonymization, encrypted import-copy cleanup, linked factor removal, free-text redactions and receipt deletion retries (build-verified; tests skipped)
- [x] Database financial before/after coverage, authenticated actor/request context, failed-password audit and shared rate-limit acceptance
- [ ] Full personal-data log-scrubbing and route permission sweep acceptance
- [x] Local HEIC-to-JPEG receipt conversion with bounded dimensions, worker timeout and strict-CSP production acceptance
- [ ] Real private scanner acceptance
- [x] Actual bank/gateway settlement matching and independent correction history
- [ ] Live mobile-money/provider acceptance with owner-supplied credentials
- [x] Real synthetic financial browser workflows and required per-package coverage gates
- [ ] Original row-level workbook acceptance, successful live dependency analysis and verified hosted CI
- [ ] Review/post private migration with separate authorized checker; collect missing actual member dates/details
- [ ] Owner production/off-site restore acceptance, then finalize release/tag. The master prompt is not declared complete.

## Backdating, shared auth limits and email administration — 2026-10-04

- [x] Read-only affected-cycle previews with exact participation/cost/expected/actual income by member number
- [x] Preserve original dates for future-only posting; require a next start after affected purchases and no future ledger dates
- [x] Maker/checker impact review; stale source, revoked maker permission, closed years and pending dependencies block reposting
- [x] Archive allocation revisions, append exact income compensation/replacement at original maturity dates and propagate later-cycle income and principal-only rollovers
- [x] Preserve explicit participation edits/exclusions, recheck committed balances and recompute snapshots
- [x] Reject replay and roll back failed approval decisions across real transaction boundaries
- [x] Historical drawer/sheet form with reviewed confirmation, promise feedback, stale-input reset and late-response rejection
- [x] Shared database-clock authentication buckets; multiple-instance/concurrency/refill/expiry acceptance and digest-only addresses
- [x] Failed-password audit omits submitted identifiers/credentials and leaves unauthenticated actor unset
- [x] Admin notification review/retry with private-payload exclusion, sent-message replay rejection and invalid-invitation delivery prevention
- [x] Upgrade React Router to 7.18.4 and Vitest to 4.1.11; npm audit reports zero vulnerabilities
- [x] Bound integration-test connection pools/context caching
- [x] Verify 178 backend tests, 250 generated allocation cases and separate Testcontainers PostgreSQL 16 smoke with all 35 migrations
- [x] Verify 61 frontend tests, production build/lint and 22 focused responsive/axe scenarios
- [x] Apply V35 to development and restart the updated backend; health UP
At this earlier pass recovery and financial browser/coverage gates remained open; later verified sections below record their completion. Later functionality checkpoints complete retention and historical imports/payment posting; dependency analysis and external acceptance remain open.

## Password and fully lost-factor recovery — 2026-10-04

- [x] Account-neutral password recovery, encrypted email links, hashed single-use tokens and 30-minute expiry
- [x] Bind links to the registered email; reject changed-email, expired, disabled-account and consumed links at completion/delivery retry
- [x] Password-only recovery retains protected methods and revokes all sessions/challenges
- [x] Full administrative reset requires documented identity verification, current maker permission and a distinct eligible checker; affected account cannot approve itself
- [x] Bind approval to current account security, revoke lost factors/codes/Google links and require new protected-method enrollment
- [x] Lock account before tokens across recovery flows; verify concurrent completion/reset rollback and replay protection
- [x] Clear secret URL fragments and avoid browser persistence; responsive administration and matching-password confirmation
- [x] Verify eight recovery integration tests, four notification tests, 66 frontend tests and four focused mobile/desktop Playwright/axe scenarios
- [ ] Live SMTP delivery acceptance remains deferred; no real email acceptance is claimed

## Real financial browser and coverage acceptance — 2026-10-04

- [x] Fresh disposable PostgreSQL and generated synthetic accounts/keys, separate application ports and automatic cleanup
- [x] Real TOTP enrollment, generated member IDs, historical seed/addition impact review, distinct checker approval and maker rejection
- [x] Real investment posting and historical allocation reverse/repost: exact 133.33/66.67 cost shares, preserved revision history and ledger-derived equity
- [x] Mobile receipt upload, transfer reference, staff verification, independent approval, duplicate decision rejection and posted member balance
- [x] FakeProvider exact-once simulation, own-data export and cross-member access rejection
- [x] Actual overview/member pages consume real test API responses; financial acceptance wrapper added to CI
- [x] Independently approved role grant/revocation, immediate backend denial and refreshed route/menu restriction
- [x] Real maturity preview/independent approval and principal-only rollover creation/approval; day passing uses an explicit synthetic date fixture in the disposable test database
- [x] Clean 187-test backend run plus 250 generated allocation cases and fresh-container migration/immutability smoke
- [x] Enforce per-package line coverage: ledger 91.41%, allocation 92.00%, dashboard 95.95%, approval 91.18%, payment 85.81%
- [x] JaCoCo report artifacts and OWASP CVSS 7+ fail-on-error CI configuration
- [ ] Successful live OWASP dependency scan and hosted CI acceptance

## HEIC receipt acceptance — 2026-10-04
- [x] Local HEIC/HEIF byte/property inspection, 10 MB and 25-megapixel limits, bounded structure traversal and 20-second worker termination
- [x] Lazy self-hosted CSP decoder, generic diagnostics and anonymous JPEG output before the standard server validation/scan path
- [x] Production bundle passes strict CSP without unsafe-eval at 375px light and 1440px dark using a generated 64×64 synthetic receipt
- [x] Four converter unit tests cover malformed/oversized inputs, pass-through, success cleanup and timeout cleanup
- [x] Distribute unmodified decoder license/source/rebuild notice and add production HEIC acceptance to CI
- [x] Full frontend regression: 70 tests, production build/lint and strict-CSP HEIC browser checks pass
- [ ] Live OWASP attempt rejected missing NVD API key; provide a valid key and pass analysis before release

## Historical-year setup — 2026-10-04

- [x] Independently approved leadership requests create missing historical calendar years without posting balances
- [x] Source reason, year bounds, duplicate/replay prevention and final maker-permission checks
- [x] Responsive drawer/sheet and checker review explain that balances require separate approval
- [x] Four backend integration tests and two frontend tests; real financial browser acceptance creates the prior year through the API before importing test balances
- [x] Full frontend regression: 72 tests pass

- [x] Clean 191-test backend regression plus 250 generated allocation checks and PostgreSQL 16 Testcontainers smoke: 36 migrations
- [x] Per-package coverage gate passes: ledger 91.93%, allocation 92.00%, dashboard 95.95%, approval 91.18%, payment 85.95%
- [x] Frontend production build/lint and repeated strict-CSP HEIC production acceptance pass

## Financial audit changes — 2026-10-04

- [x] V37 captures transactional before/after changes for ledger, investments, projected/actual allocations, payments, fiscal periods/snapshots, policies, reconciliation and approval decisions
- [x] Explicit field allowlists omit free-text notes, bank/provider references, private payloads and member identity/contact details; decimal values remain strings
- [x] Immutable separate change records, transaction correlation, timestamp-only suppression and rollback acceptance
- [x] Authenticated actor context cannot come from headers; invalid sessions/background pool checkout clear it
- [x] Authorized audit screen and formula-safe CSV expose recorded financial changes; original arbitrary audit payloads remain excluded
- [x] Five integration tests, 73 frontend tests, build/lint and four focused mobile-light/desktop-dark axe checks pass
- [x] Clean 196-test backend regression, 250 generated allocation checks, all 37 migrations in PostgreSQL 16 Testcontainers and all financial package coverage gates pass
- [x] Real browser/API acceptance verifies earned-income compensation/repost (13.34/6.66), separate loss (-10), receipt posting before/after and exact independent-checker attribution

## Receipt amendments and final regression — 2026-10-04

- [x] V38 preserves append-only receipt revisions, original private files and owned data-export history
- [x] Member/verifier replacement before verification, independently owned uploads, stale-form rejection and archived-proof reuse prevention
- [x] Serialize replacements against verification; concurrent verification cannot have its reviewed evidence overwritten
- [x] Read-only history remains available to authorized members/checkers after posting; replacement uses the shared drawer/sheet and local HEIC conversion
- [x] Retry retains the uploaded replacement instead of re-uploading; amounts, purpose and paid date remain unchanged
- [x] Five backend integration tests, 76 frontend tests, production build/lint and mobile/desktop receipt axe acceptance
- [x] Clean 201-test backend regression plus 250 generated allocation cases and fresh PostgreSQL 16 Testcontainers smoke with all 38 migrations
- [x] Coverage gates: ledger 91.93%, allocation 92.00%, dashboard 95.95%, approval 91.18%, payment 88.09%
- [x] Full 190-scenario Playwright/axe suite passes at 320/375/768/1024/1440 in both themes
- [x] Real financial browser replacement retains a downloadable original, records owned history and rejects posted changes
- [x] Fix compact member mobile navigation appearing on desktop; ignore generated browser artifacts in Vite watchers
- [x] Restore development backend with V38; health UP, and restart the development frontend

Additional master-brief gaps identified during final comparison:

- [x] Configurable private S3-compatible receipt storage adapter and encrypted backup/restore integration; real-bucket IAM/TLS/off-site owner acceptance remains open
- [x] Move member/account name and email searches from query strings into protected request bodies

Historical checkpoint: the listed implementation gaps were subsequently closed in the Functionality completion pass below. Final privacy/security acceptance remains a release requirement. Live providers, SMTP, scanner, NVD analysis, hosted CI, reviewed private migration and owner production/restore acceptance remain delivery gates.

- [x] Refresh ten synthetic role-guide screenshots, reset scroll before capture, visually inspect audit/ledger and pass all 11 desktop guide scenarios

## Protected search and private storage — 2026-10-05

- [x] Member directory/pickers and account administration use POST search bodies; URLs retain only safe pagination values
- [x] Reject legacy identifying query searches with generic responses; preserve session, current permission, CSRF, same-origin and no-store checks
- [x] Captured synthetic search sentinel is absent from application logs; response errors do not echo submitted identifiers
- [x] Unsigned/ordinary-member denial acceptance across 21 existing union read routes; the broader mutation/log-payload review remains open
- [x] Missing routes return a generic 404 instead of an internal error
- [x] Private local/S3 object-store interface, anonymous conditional S3 creates, bounded reads, server-only ownership checks and generic storage errors
- [x] Downloads verify original SHA-256/size; tampered/missing receipts fail and unauthorized requests never contact object storage
- [x] Production S3 configuration rejects HTTP and disabled encryption overrides; default development storage remains local
- [x] Six Python backup/retention checks pass, including complete inventory verification before upload and generic subprocess diagnostics
- [x] Clean 208-test backend regression plus 250 generated allocation cases; coverage ledger 92.21%, allocation 92.00%, dashboard 95.95%, approval 91.18%, payment 88.33%
- [x] Three additional storage-configuration tests and the focused object/integrity tests pass
- [x] Frontend 76 tests, production build/lint and 28 focused mobile-light/desktop-dark Playwright/axe checks pass
- [x] Real financial browser/API workflow passes with protected search and receipt integrity checks
- [x] Updated encrypted local recovery drill passes: exact 1200.0000 ledger total, receipts, occupied-target and wrong-key rejection
- [x] S3 protocol container acceptance: exact bytes, conditional collision rejection, anonymous denial and missing-object errors; encrypted separate-bucket recovery and occupied-prefix rejection
- [x] Final clean 211 backend tests plus 250 generated allocation cases, two container tests (PostgreSQL/S3), all 38 migrations and financial coverage gates pass

Historical checkpoint: later sections close the listed historical workflows, retention and settlement implementation. Complete privacy/security acceptance remains a release requirement. Provider credentials, SMTP (owner-deferred), live scanner/NVD/hosted CI, independently reviewed private migration and owner production/off-site restore acceptance remain external delivery gates. No real member balances were posted in this pass.

- [x] Development backend restored with the verified private-storage/search build; health UP

## Bank settlement matching — 2026-10-05

- [x] V39 retains append-only settlement review history with encrypted bank-evidence notes and separate safe financial audit metadata
- [x] List confirmed, ledger-linked gateway payments with gross/provider fee/expected net and current bank amount/date
- [x] Independent approval, current maker permission, SHA-256 source binding, duplicate pending-request and unchanged-value rejection
- [x] Correct prior settlements through new reviewed records; do not post another contribution or rewrite the original evidence
- [x] Enforce confirmation-through-today dates and nonnegative two-decimal bank amounts; retain explained discrepancies
- [x] Permission/session/CSRF/origin denial, replay rejection, stale-review rollback, maker-revocation rejection and concurrent submission acceptance
- [x] Reconciliation fingerprints include bank settlement values; gateway posting shares the financial lock before row/year locks
- [x] Drawer/bottom-sheet creation and review, promise feedback, empty/error states, current-role controls and server permission checks
- [x] Four settlement integration tests plus existing reconciliation/online tests pass; 79 frontend tests and build/lint pass
- [x] Twenty-four focused mobile-light/desktop-dark Playwright/axe checks pass, including settlement creation and checker review
- [x] Clean 215 backend tests, 250 generated allocation cases, two container tests, all 39 migrations and per-package financial coverage gates pass
- [x] Real mobile settlement submission and independent browser approval in the disposable financial lifecycle; exact bank net/date, replay denial and unchanged member equity verified

Historical checkpoint: the Functionality completion pass below closes the historical-workflow and retention implementation gaps. Complete privacy/security acceptance remains a release requirement. Live providers, owner-deferred SMTP, scanner/NVD/hosted CI, reviewed private migration and owner production/off-site recovery acceptance remain delivery gates. The master prompt is not yet complete.

- [x] Restore the verified V39 development backend; health UP


## Functionality completion pass — 2026-10-05

The owner explicitly requested implementation instead of another test pass. No automated tests were run for this section; build/startup checks are recorded separately.

- [x] Historical manual-payment preview and verification derive the original date, member and purpose from the submitted receipt; bind the reviewed financial source and retain exact-once payment ledger linkage
- [x] Checker reviews the original-date/future-only choice and allocations before approval; stale financial sources require cancellation and a fresh preview
- [x] Payment review uses the shared desktop drawer/mobile sheet, multiline promise feedback and permission-aware historical controls
- [x] V40 and CSV/XLSX templates import one historical investment with its member allocations as an encrypted, independently approved, reversible batch
- [x] Validate participating members, source dates, available historical equity, open periods, duplicate references, exact optional share columns and a supplied TOTAL row
- [x] Distinguish expected future value from received proceeds; warn against counting received income again when it is already in an opening balance
- [x] Reverse an unchanged imported cycle with current-day exact earned-income compensation, preserved revision evidence and dependency/fund checks
- [x] Authorized archived-revision drawer/sheet reconstructs original cycle dates, exact projected allocations and actual received shares
- [x] Original-date received-proceeds correction previews and independently reposts later current cycles, preserves edits/exclusions/rollover caps, compensates original earned-income entries and versions fiscal snapshots
- [x] V41 adds seven-year default personal-data retention, confirmed departure dates, permanent-removal previews, independent approval, tombstones and irreversible account/membership reactivation guards
- [x] Remove private member fields, linked authentication factors/tokens/outbox copies and associated encrypted import identities; redact identifying evidence while preserving financial amounts, dates and UUID links
- [x] Narrow digest-bound database redaction authorization permits only reviewed private-field changes; financial ledger values, history and ordinary append-only protections remain
- [x] Block receipt downloads immediately after approved retention and retry physical local/S3 deletion; current backups omit tombstoned files, including queued deletions
- [x] Group retention policy with organization policies; show legacy missing departure dates, due memberships, pending independent decisions and storage deletion backlog
- [x] Static review of the new route permissions, session/CSRF/origin boundaries, source binding and generic error/log handling
- [x] V42 retains append-only economic correction evidence and links earned-income adjustments to their exact compensating entries
- [x] Recompute moved and reversed cycle segments, preserving original revisions and their independently approved compensation dates
- [x] Reviewed original-date reversal of manual contributions and principal/income opening balances, with dependent-cycle previews and negative-equity protection
- [x] Member JSON export includes only their own archived corrections and adjustment links; compensated receipt credits are explained on screen and confirmation PDFs
- [x] Backend package and frontend production build succeed with tests skipped; development backend applies V42 and reports health UP

Implementation status: the outstanding historical workflows and retention function identified in this checklist are implemented. Moved-cycle corrections now reconstruct independently approved economic segments rather than inventing missing dates. New financial/destructive workflows are build-verified, not newly test-verified: the owner explicitly waived tests on this pass. Production delivery remains separate from local feature completion.

External delivery requirements: owner-deferred SMTP/MailHog; Google/payment credentials; live private ClamAV and NVD/hosted CI acceptance; independent review of real balances and missing source dates; production hosting, private bucket/IAM, keys and owner off-site restore acceptance. Existing original workbooks and earlier encrypted backups are managed by the owner’s documented retention process; local application anonymization cannot erase other copies.


## Final main-build wiring and handoff — 2026-10-05

- [x] Include retention, historical-income correction and organization controllers in the outer deny-by-default security route/CSRF configuration; inner session, current permission and ownership checks remain required
- [x] V43 configurable minimum password length and session idle timeout, enforced at password changes and every access/refresh resolution
- [x] Organization name/logo applied to landing/header/page subtitles; PNG normalization and bounded public logo, primary-colour contrast validation and GHS symbol/code display
- [x] Keep branding and security controls within the organization policies block; separate permission-protected storage/backup information card makes no backup-freshness claim
- [x] Generated non-production OpenAPI route/request-schema document at /api/docs, denied and absent under prod
- [x] Actor/body/route-bound successful financial JSON replay records commit atomically with financial effects; only opaque UUID acknowledgements are retained, with current session/CSRF/origin/permission rechecks
- [x] Shared frontend in-memory retry keys survive ambiguous network/server failures without retaining form values or storing keys on disk
- [x] Investment institution/reference search, status/sort filters and server pagination; safe page/status/sort filters persist in the URL while search text stays in the protected request body
- [x] Production Compose provisions private ClamAV with persistent signatures, bounded memory and health-gated startup; production rejects an unconfigured scanner
- [x] Central OWNER_ACTIONS.md specifies owner/client credentials, assumptions, SMTP deferral, scanner/hosting/storage setup, repository and production acceptance

Build/startup verification is recorded after packaging. No test suite was run during this implementation pass; next work is the isolated suite and fixes it identifies. It is not possible to guarantee that unrun tests will identify zero defects. All private source and production acceptance remain in OWNER_ACTIONS.md rather than being marked passed.


- [x] Final backend package with tests skipped, frontend production build and production Compose configuration succeed
- [x] Development startup reports health UP with V43; local /api/docs serves OpenAPI 3.0.3 and retention/income-correction/organization routes return 401 unsigned
- [x] Add explicit TEST_PLAN.md for the isolated regression and new financial/privacy/UI checks that follow implementation
- [x] Preserve pre-existing frontend working edits; stage only the new functional changes in mixed files


## Authorized local verification — 2026-10-05

- [x] Isolated full backend regression; focused historical receipt checks and both PostgreSQL/S3 container checks
- [x] Financial line coverage gate passes without lowering thresholds
- [x] 81 frontend unit tests, lint and production build
- [x] 210 responsive/accessibility scenarios across five widths and both themes, including organization policies and retention preview
- [x] Real synthetic HTTP/browser financial lifecycle and permission revocation
- [x] Encrypted local/S3 restore drills, exact totals, privacy-aware receipt inventory and target/key safeguards
- [x] Development backend restored and health UP with V43
- [x] Record exact counts, fixture fixes, nonblocking warnings and unexercised verification scope in TEST_RESULTS.md

No known main-build implementation blocker was found. OWNER_ACTIONS.md remains the credential/client/production handoff. The broader edge-case matrix in TEST_PLAN.md is not silently certified by these passing local gates.
