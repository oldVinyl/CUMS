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
- [x] Flyway V1–V9 core financial, role, approval, fiscal snapshot, and authentication-state migrations tested against PostgreSQL 16
- [ ] Add remaining workflow tables and migration tests
- [x] Structured API validation and access errors with trace IDs
- [x] Append-only ledger and audit triggers with database tests
- [x] AES-256-GCM private-field encryption, versioned keys, and blind indexes with tests
- [ ] Audit service and sensitive-access logging
- [x] Argon2id password hashing and basic common-password policy with tests
- [x] Session, login-attempt, and TOTP recovery-code tables
- [x] Opaque access/refresh/CSRF token service with hashed storage, rotation, replay revocation, and tests
- [x] RFC 6238 TOTP code generation and adjacent-step verification with standard test vectors
- [x] Database-backed login throttling and exponential account lockout with keyed identifier digests
- [x] Password first-factor verification with generic failures and tests
- [x] Password-change and TOTP challenges with encrypted enrollment secrets, attempt limits, replay protection, and integration tests
- [x] HTTP sign-in, refresh, logout, and profile endpoints with strict cookies, origin and CSRF checks, and integration tests
- [ ] Complete HTTP rate limiting, recovery codes, logout everywhere, and authenticated route permission enforcement
- [x] Seed six roles and permission grants; reject overlapping active assignments
- [x] Read live permissions on each check; approved role changes take effect immediately
- [x] Block revocation of the last active ADMIN or PRESIDENT through role-change approvals
- [ ] Enforce permissions on authenticated endpoints and protect against expiry/disable removing the last officer
- [x] Responsive frontend shell, light/dark theming, money formatter, feedback toasts, and Vinette credit
- [x] Server-connected sign-in, password-change, and TOTP screens with toast feedback
- [ ] Replace preview pages with live permission-aware navigation and financial data

## Phase 2 — Members and ledger
- [ ] Member CRUD and encrypted fields
- [x] Transactional maker-checker approval service with distinct approvers and atomic action execution
- [x] Approved current-day seed/addition posting to the immutable ledger
- [x] Approved current-day manual contribution reversal with exact negative amount and reason
- [x] Member balances computed from ledger entries through an as-at date
- [x] Approved year-end close with balance-forward snapshots and fiscal-year locking
- [ ] Historical corrections, backdating impact preview, period unlock, and snapshot recomputation
- [ ] Attachment security and ledger UI

## Phase 3 — Investments
- [x] Pesewa allocation engine and property tests
- [ ] Investment lifecycle, rollover, reversal
- [ ] Sheet A screen and fixture test

## Phase 4 — Dashboards and exports
- [x] Persist balance-forward snapshots at approved year-end close
- [ ] As-at dashboard, Sheet B calculations, and filters
- [ ] Sheet B fixture test, statements, xlsx/PDF, charts

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
