# Release checklist

Check only implemented and verified gates. This system is not yet production complete.

- [x] Member CRUD, generated IDs, encryption and permissions
- [x] Immutable ledger, current-day contributions/reversals and independent approval
- [x] Investment lifecycle and current-day compensating revision history
- [x] Dashboard and Excel/PDF financial reports with supplied fixtures
- [x] Private receipt submission/review and approved exact-once payment posting
- [x] Signed webhook jobs, strict provider verification and dev-only simulator
- [x] Member portal, invitation activation/replacement and own-data ownership tests
- [x] Reviewed role-management UI and current permissions
- [x] Current-day reconciliation snapshots and source freshness checks
- [x] Persistent email outbox and opt-in SMTP implementation
- [x] Responsive fixture checks at 320/375/768/1024/1440 in both themes
- [x] 40 Playwright/axe scenarios for public/sign-in/overview/settings/member creation in five sizes and both themes
- [x] Expand to 140 Playwright/axe scenarios including ten operational screens and provide synthetic role-guide screenshots
- [x] Real synthetic financial browser workflows: historical year/contributions, maturity/rollover, receipt/provider posting and role revocation
- [x] Full 190-scenario responsive Playwright/axe regression in both themes
- [ ] Final production security/release acceptance
- [x] Reviewed bank/gateway settlement matching and correction history
- [ ] Real Paystack sandbox and live MoMo/provider acceptance
- [ ] SMTP/MailHog delivery and operational retry acceptance
- [x] Historical contribution impact preview, future-only/repost, period unlock and snapshot recomputation
- [x] Approved reasoned period unlock, immutable snapshot revisions, later-year recomputation and reviewed historical import integration
- [x] Overview fiscal-year selector and component/missing-member snapshot reconciliation
- [x] CSV/XLSX member/opening-balance/contribution dry runs, approval and batch compensation
- [x] Historical investment/allocation imports, archived economic correction and dependent-cycle repost (build/startup verified; tests waived this pass)
- [x] Filterable audit metadata screen and audited CSV export with authorization and injection tests
- [x] Own-account security controls, protected verification, passkey revocation, recovery-code replacement and password changes
- [x] Fully lost-factor recovery and independent account-reset administration
- [x] Transactional financial before/after audit, private-field exclusion and authenticated checker attribution
- [ ] Full personal-data log-scrubbing acceptance
- [ ] Complete original Sheet B workbook acceptance
- [x] Local HEIC-to-JPEG conversion, bounded input/dimensions/time and production CSP browser acceptance
- [ ] Real malware scanner and final file security acceptance
- [ ] Production CSP/TLS/security review and deployment
- [x] Production Compose/Caddy configuration, startup guards, separate leadership bootstrap and synthetic encrypted recovery tooling
- [x] Local age-encrypted database/receipt restore drill with exact totals, wrong-key and populated-target rejection
- [ ] Encrypted off-site database/files backups, retention and restore drill
- [x] Audited owned JSON export, decimal preservation, private response headers and IDOR/secret-exclusion tests
- [x] Independently approved retention/anonymization, private-copy redaction, tombstones and receipt deletion retries (build/startup verified; tests waived this pass)
- [ ] Owner retention policy review and management of external copies/backups

No production deployment or successful external service delivery is claimed.

- [x] Reviewed receipt amendments, immutable proof history and owned export
- [x] Configurable private S3-compatible receipt storage and encrypted recovery integration
- [x] Personal-search request-body privacy
- [ ] Final comprehensive route/log security acceptance


## Completion-pass implementation

- [x] Historical manual receipt posting and independent impact review
- [x] Original-date manual contribution/opening-balance compensation with reviewed cycle/snapshot recomputation
- [x] Original received-proceeds correction for current and applicable moved/reversed economic revisions
- [x] Read-only original investment evidence plus append-only economic correction history
- [x] Owner-only archived correction/adjustment export and compensated-payment explanations
- [x] Backend/frontend builds and development startup with V42; no test suites run as explicitly requested

These checks record implementation and build/startup verification. They do not certify unrun tests or production financial/privacy acceptance. Remaining unchecked items require external credentials, private source review, deployment or release acceptance, rather than another generic implementation continuation.


## Final main-build completion

- [x] Missing outer route registration for retention/historical income fixed
- [x] Branding/logo, primary colour, currency display, password policy and session idle timeout implemented
- [x] Current union identity applied consistently across page headings and first-password setup
- [x] Non-production OpenAPI catalogue and financial Idempotency-Key replay support
- [x] Paginated/searchable/sortable investment directory beyond the previous 100-cycle cap
- [x] Production scanner provisioning and configuration guards
- [x] Owner tasks consolidated in OWNER_ACTIONS.md
- [x] Isolated regression and focused new-workflow checks; actual results and additional unexercised scope in TEST_RESULTS.md

Remaining owner inputs and deployment acceptance: follow [OWNER_ACTIONS.md](OWNER_ACTIONS.md). Build success does not mark the pending suite or owner gates as passed.

Final build checkpoint: backend/frontend builds and production Compose configuration succeed; the development server is healthy with V43. Unsigned new administration routes require sign-in. The next verification work is documented in TEST_PLAN.md; no suite result is invented.

Latest verification: local backend/frontend/browser/financial/restore checks passed on 2026-10-05. See TEST_RESULTS.md; owner release gates remain separate.
