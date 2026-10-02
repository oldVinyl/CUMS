# Progress

Check an item only after implementation and tests pass. This list mirrors the master brief's phase plan.

## Phase 0 — Setup
- [x] Initialize Git and `.gitignore`
- [x] Add README and initial SETUP, ARCHITECTURE, PROGRESS, ASSUMPTIONS, OPEN_QUESTIONS
- [x] Docker Compose development services defined and config validated (runtime blocked by unavailable Docker daemon)
- [x] Spring Boot skeleton, health endpoint, unit smoke test
- [x] React/Vite skeleton
- [x] CI workflow, gitleaks configuration, ESLint
- [ ] Run backend against PostgreSQL and hit health endpoint (Docker daemon unavailable here)
- [ ] Java and frontend formatter configuration

## Phase 1 — Foundation
- [ ] Flyway baseline, errors, audit, encryption
- [ ] Authentication, sessions, CSRF, rate limiting, TOTP
- [ ] Roles, permissions, assignment rules
- [ ] Responsive frontend shell, auth screens, theming, money formatter (shell, theme, formatter and preview done; auth screens pending)

## Phase 2 — Members and ledger
- [ ] Member CRUD and encrypted fields
- [ ] Immutable ledger, fiscal years, backdating, approvals
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
