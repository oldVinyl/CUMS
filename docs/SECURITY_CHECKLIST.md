# Security acceptance checklist

This tracks the master prompt's security requirements. A checked implementation item is not production approval. Real personal data remains local and excluded from Git; test data is synthetic.

## Authentication and accounts

- [x] Argon2id password hashing, minimum-length/common-password rejection and forced first-login password replacement
- [x] Persisted account lockouts and keyed account/IP attempt identifiers
- [x] One-use TOTP enrollment/verification, hashed one-use recovery codes and verified discoverable WebAuthn passkeys
- [x] Own-account password changes and passkey revocation require a fresh password plus authenticator/recovery code or verified passkey
- [x] Passkey revocation denies future assertions, retains credential metadata and revokes all sessions; removing the last protected sign-in method is blocked
- [x] Recovery-code replacement invalidates all old codes and shows replacements once; no browser storage of codes
- [x] Origin/CSRF, anonymous-access and foreign-credential rejection tests for account-security routes
- [x] Opaque hashed sessions, refresh rotation/replay revocation and account-wide logout
- [x] Recovery when every protected factor and recovery code is lost, with independently authorized administrative reset
- [ ] Live password-reset delivery acceptance
- [ ] Distributed HTTP rate-limit acceptance and a complete anonymous/permission route sweep

## Financial authorization and integrity

- [x] Database-enforced immutable ledger/audit records and exact compensation
- [x] Distinct-maker approvals, current permissions, stale-source protection and permission-filtered navigation
- [x] Server-derived member ownership for profile/statements/payments/receipts/data exports, with IDOR tests
- [x] Four-decimal amounts and deterministic exact allocation rounding
- [ ] Historical dependent-investment corrections/backdating impact acceptance and complete row-by-row original workbook acceptance
- [ ] Expand idempotency/concurrency and end-to-end acceptance to every financial action

## Data, uploads and audit

- [x] AES-256-GCM versioned private fields and keyed blind indexes
- [x] Private receipt storage, bounded size/magic-byte checks, metadata stripping and audited downloads
- [x] Parameterized queries, safe structured errors, formula-safe exports and data-minimal audit events
- [x] Private ClamAV INSTREAM hook scans original/normalized receipts and fails closed on malware, malformed responses or unavailable configured daemon; bounded protocol tests
- [x] Local bounded HEIC conversion and strict-CSP production acceptance
- [ ] Live private scanner acceptance
- [x] Request IP/trace enrichment for all database audit inserts, trusted production gateway forwarding and pooled-connection/background isolation tests
- [x] Request IP/trace/actor enrichment and immutable financial before/after records with explicit private-field exclusion
- [ ] Full personal-data log-scrubbing acceptance
- [ ] Retention-based anonymization of inactive members and historical personal-data copies
- [x] Encrypted database/upload backup scripts, exact-name retention, empty-target restore protection and passing isolated synthetic restore drill
- [ ] Real off-site credentials, delivery, monitoring and owner recovery acceptance

## Browser and delivery

- [x] Playwright/axe harness for public pages, sign-in, overview, settings, recovery overlays and member creation at 320/375/768/1024/1440 in light/dark themes
- [x] Browser tests intercept API calls and never post to the real development financial database
- [x] Expand the harness to ledger, investments, payments, reconciliation, portal, reports, accounts, imports, audit and approvals; all 140 responsive/axe scenarios pass
- [x] Production-only static PWA cache excludes API/documents; private responses use no-store headers
- [ ] Finish axe/keyboard acceptance across payments, investment/approval/reconciliation screens and real financial browser workflows
- [ ] Production HTTPS/CSP/cookie review, dependency scan, coverage thresholds and verified CI acceptance
- [x] Resolve React Router/Vitest advisories with 7.18.4/4.1.11 and verify zero npm-audit vulnerabilities plus frontend regression/build/lint acceptance

## External services

- [ ] Google OAuth credentials and real-provider sign-in acceptance (flow remains gated)
- [ ] Paystack sandbox/live credentials, real webhook and settlement acceptance
- [ ] SMTP delivery acceptance; MailHog remains explicitly deferred by the owner

Do not tag v1.0.0 or mark the master prompt complete while these gates remain open.

## Additional verified acceptance — 2026-10-04

- [x] Shared PostgreSQL authentication limits across instances, atomic overspend protection, refill/expiry and address-digest privacy checks
- [x] Failed-password audit omits submitted identifiers/credentials and does not attribute an unauthenticated actor
- [x] Historical contribution checksums, period/permission guards, independent impact review and transaction rollback tests
- [x] Notification session/permission/CSRF/origin gates, private-payload exclusion and replay/expired-invitation rejection
- [x] Fresh PostgreSQL 16 Testcontainers migration and immutable ledger acceptance

## Account recovery acceptance — 2026-10-04

- [x] Public account-neutral reset, registered-email binding, encrypted outbox and hashed expiring single-use links
- [x] Password reset retains protected methods; full reset requires independent current-role approval and fresh enrollment
- [x] Changed-security rejection, affected-account exclusion, concurrent completion/reset and transaction rollback acceptance
- [x] Responsive reset/admin review with matching-password confirmation and secret-fragment removal
- [ ] Live recovery-email delivery and owner identity-verification acceptance
