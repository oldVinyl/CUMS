# Progress

Check an item only after implementation and tests pass. This list mirrors the master brief's phase plan.

## Phase 0 — Setup
- [x] Initialize Git and `.gitignore`
- [x] Add README and initial SETUP, ARCHITECTURE, PROGRESS, ASSUMPTIONS, OPEN_QUESTIONS
- [ ] Docker Compose development services
- [ ] Spring Boot skeleton, health endpoint, smoke test
- [ ] React/Vite skeleton
- [ ] CI, secret scan, formatters, linters

## Phase 1 — Foundation
- [ ] Flyway baseline, errors, audit, encryption
- [ ] Authentication, sessions, CSRF, rate limiting, TOTP
- [ ] Roles, permissions, assignment rules
- [ ] Responsive frontend shell, auth screens, theming, money formatter

## Phase 2 — Members and ledger
- [ ] Member CRUD and encrypted fields
- [ ] Immutable ledger, fiscal years, backdating, approvals
- [ ] Attachment security and ledger UI

## Phase 3 — Investments
- [ ] Pesewa allocation engine and property tests
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

