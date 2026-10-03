# Progress

Check an item only after implementation and tests pass. Each item is small enough to show partial phase progress accurately.

## Phase 0 — Setup
- [x] Initialize Git and `.gitignore`
- [x] Add README and initial SETUP, ARCHITECTURE, PROGRESS, ASSUMPTIONS, OPEN_QUESTIONS
- [x] Docker Compose development services defined and config validated
- [x] Spring Boot skeleton, health endpoint, unit smoke test
- [x] React/Vite skeleton
- [x] CI workflow, gitleaks configuration, ESLint
- [x] Start PostgreSQL 16 and verify API health and protected-route denial against it
- [ ] Confirm MailHog starts (deferred at the owner's request after repeated Docker Hub pull failures; revisit before email notifications and Phase 8 acceptance)
- [ ] Testcontainers PostgreSQL smoke test (local dependency download failed; PostgreSQL startup was verified manually)
- [ ] Java and frontend formatter configuration

## Phase 1 — Foundation
- [x] Flyway V1–V21 core financial, role, approval, fiscal snapshot, authentication, member identity, Google link, passkey, investment allocation, maturity, rollover, settings, and admin investment-read migrations tested against PostgreSQL 16
- [ ] Add remaining workflow tables and migration tests
- [x] Structured API validation and access errors with trace IDs
- [x] Append-only ledger and audit triggers with database tests
- [x] AES-256-GCM private-field encryption, versioned keys, and blind indexes with tests
- [x] Data-minimal append-only audit service and authentication security events
- [x] Sensitive member-detail reads audited without storing private values in the event
- [x] Audit report/statement generation and downloads without recording private report contents
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
- [ ] Distributed HTTP rate limiting, recovery-code regeneration, and authenticated route permission enforcement
- [x] Seed six roles and permission grants; reject overlapping active assignments
- [x] Read live permissions on each check; approved role changes take effect immediately
- [x] Give the configured local bootstrap account every known permission only under the `dev` profile, without weakening production ADMIN grants or maker-checker separation
- [x] Block revocation of the last active ADMIN or PRESIDENT through role-change approvals
- [x] Enforce current role permissions and session ownership on member API endpoints
- [ ] Protect against role expiry or account disable removing the last ADMIN or PRESIDENT; extend permission checks to later APIs
- [x] Responsive frontend shell, light/dark theming, money formatter, feedback toasts, and Vinette credit
- [x] Server-connected sign-in, password-change, and TOTP screens with toast feedback
- [x] Scannable TOTP setup QR with a manual key fallback
- [x] Google ID token verification, one-use nonce, explicit account linking, and gated UI for configured client IDs
- [x] WebAuthn passkey registration and assertion services with stored keys, one-use ceremonies, and user verification
- [x] Browser passkey registration, sign-in, preference, and replay rejection with a virtual authenticator against a disposable PostgreSQL database
- [ ] Test passkey device-loss recovery and credential removal paths
- [x] Require every account to choose a protected sign-in method on first sign-in
- [x] React Hot Toast themes for light/dark success, information, and error feedback with multiline detail and promise feedback for member mutations
- [x] Offer TOTP, passkey, and gated Google sign-in to every account, including first sign-in preference setup
- [x] Signed-in member directory and creation form use live API with role-aware controls and toast feedback
- [ ] Replace remaining preview pages with live permission-aware navigation and financial data

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
- [ ] Historical corrections, backdating impact preview, period unlock, and snapshot recomputation
- [x] Live paginated ledger UI, contribution/reversal/year-close request controls, maker cancellation, confirmation-based approvals, origin/CSRF and permission tests
- [x] Recheck committed investment funds before approving manual contribution compensation
- [ ] Attachment security and secure downloads

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
- [ ] Investment lifecycle, rollover, reversal
- [x] Approved current-day investment reversal with exact gain/loss compensation, immutable revision history, dependency checks, return to draft and fresh posting approval
- [ ] Historical investment correction dates and approved period unlock/recomputation
- [x] Sheet A xlsx/PDF downloads with grouped headings, reconciled supplied fixture totals, permissions, audit and visual checks

## Phase 4 — Dashboards and exports
- [x] Persist balance-forward snapshots at approved year-end close
- [x] Serve an authorized live dashboard for any as-at date with balance forward, year-to-date, totals, ROE, equity ratios, and member search
- [x] Restore overview summary cards, add monthly ledger equity trends, period views and sorting, and replace the wide table with compact desktop rows and expandable mobile cards
- [x] Verify overview empty and error states, light/dark themes, and desktop/mobile layouts without page overflow
- [x] Provide opt-in, idempotent fictitious member and ledger samples scoped to the dev profile
- [x] Separate Settings controls into individual cards and adapt the Vinette credit for smaller screens
- [x] Verify the supplied Sheet B aggregate totals, ROE/equity-share examples, and exclusion of future effective entries with an independent ledger fixture
- [ ] As-at dashboard, Sheet B calculations, and filters
- [x] Sheet B xlsx/PDF downloads with grouped headings, numeric totals, audit and visual checks
- [x] Member statements with opening/running/closing balances, date range, PDF/xlsx, staff authorization and own-account IDOR tests
- [x] Live Reports page and statement downloads in member details
- [x] Ledger and public-field member-directory Excel/PDF exports with range/date filters and permission tests
- [x] Check new financial forms and review screens at desktop/mobile widths in both themes, including search styling and secondary-action contrast
- [ ] Complete original Sheet B row-by-row workbook acceptance and snapshot reconciliation

## Phase 5 — Payments
- [ ] Provider interface, fake and Paystack implementations
- [ ] Webhook, verification, receipt workflow, notifications

## Phase 6 — Member portal
- [ ] Invitation, own-data dashboard and history, profile, export
- [ ] Ownership and IDOR tests

## Phase 7 — Import and reconciliation
- [ ] Dry-run importer, approval and batch reversal
- [ ] Reconciliation workflow

## Phase 8 — Delivery
- [ ] Security and accessibility review, responsive E2E, PWA
- [ ] Production deployment, backups and restore drill
- [ ] User guide, runbook, privacy, checklist, full test gates

## Deferred follow-ups
- [ ] MailHog startup and email notifications remain deferred at the owner's request.
- [ ] Google sign-in stays gated until OAuth credentials are supplied.
- [ ] Complete passkey device-loss recovery and credential removal after the core financial workflows.
