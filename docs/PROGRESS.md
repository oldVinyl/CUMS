# Progress

Check an item only after implementation and tests pass. This list mirrors the master brief's phase plan.

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
- [ ] Flyway baseline, errors, audit, encryption (V1–V3 core schema, derived balances, append-only ledger/audit triggers, AES-GCM field encryption, and structured API errors with tests done; remaining workflow tables and audit service pending)
- [ ] Authentication, sessions, CSRF, rate limiting, TOTP
- [ ] Roles, permissions, assignment rules (six roles, permission grants, database overlap protection, and immediate permission checks tested; endpoint authorization and last-admin/president safeguards pending)
- [ ] Responsive frontend shell, auth screens, theming, money formatter (shell, theme, toasts, formatter and preview done; auth screens pending)

## Phase 2 — Members and ledger
- [ ] Member CRUD and encrypted fields
- [ ] Immutable ledger, fiscal years, backdating, approvals (approval service, approved current-day contributions, and as-at balances across leap and fiscal-year boundaries tested; reversal workflow, period operations, and backdating pending)
- [ ] Attachment security and ledger UI

## Phase 3 — Investments
- [x] Pesewa allocation engine and property tests
- [ ] Investment lifecycle, rollover, reversal
- [ ] Sheet A screen and fixture test

## Phase 4 — Dashboards and exports
- [ ] As-at dashboard and snapshots
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
